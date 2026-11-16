package chatapp.server;

import chatapp.common.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 서버의 공유 상태(접속자·방)와 요청 처리. 모든 공개 메서드는 하나의 락으로 직렬화한다.
 * 요청 검증은 클라이언트 검사와 무관하게 서버에서 다시 수행한다.
 * Session.send는 전송 대기열에 넣기만 하므로, 락 안에서 여러 연결에 보내도 느린 연결 때문에 멈추지 않는다.
 * Session.send는 전송 대기열에 넣기만 하므로, 락 안에서 여러 연결에 보내도 느린 연결 때문에 멈추지 않는다.
 *
 * 정책(docs/protocol.md의 공동 확정 사항):
 *  - 한 사용자는 한 방에만 있을 수 있다. 방에 있는 상태의 ROOM_CREATE/ROOM_JOIN은 ERROR(INVALID_ROOM).
 *    (클라이언트는 ROOM_LEAVE → ROOM_LEFT 확인 후 다음 방을 요청한다.)
 *  - 참가자가 0명이 된 방은 삭제한다.
 *  - 접속이 끊기면 닉네임을 바로 다시 쓸 수 있다.
 *  - 방 목록·인원 변동은 접속자 전체에게 ROOMS로 보낸다.
 */
final class Hub {
    /** 이모티콘 고정 목록(E01~E08). 그림은 프론트가 같은 ID로 그린다. */
    static final List<String> EMOTICON_IDS = List.of("E01", "E02", "E03", "E04", "E05", "E06", "E07", "E08");
    /** key가 value를 이긴다. */
    private static final Map<String, String> BEATS = Map.of("ROCK", "SCISSORS", "SCISSORS", "PAPER", "PAPER", "ROCK");
    private static final List<String> CHOICES = List.of("ROCK", "PAPER", "SCISSORS");

    static final class Room {
        final String id;
        final String name;
        final Set<Session> members = new LinkedHashSet<>();
        Game game;

        Room(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 방 하나에서 진행 중인 가위바위보 한 판. 참가자는 시작 시점의 방 전원으로 고정된다. */
    static final class Game {
        final String id;
        final Set<Session> participants;
        final Map<Session, String> choices = new LinkedHashMap<>();
        ScheduledFuture<?> timer;

        Game(String id, Set<Session> participants) {
            this.id = id;
            this.participants = participants;
        }
    }

    private final Map<String, Session> users = new HashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    /** 닉네임 → 보유 이모티콘 ID. users와 별도로 두어 접속 종료에도 유지한다. 서버 재시작 시 초기화. */
    private final Map<String, Set<String>> emoticons = new HashMap<>();
    private final ScheduledExecutorService timers;
    private final long choiceTimeoutMillis;
    private final Random random;
    private int nextRoomNumber = 1;
    private int nextGameNumber = 1;

    Hub() {
        this(30_000, new Random());
    }

    /** 테스트에서 선택 제한 시간과 난수를 주입할 수 있게 하는 생성자. */
    Hub(long choiceTimeoutMillis, Random random) {
        this.choiceTimeoutMillis = choiceTimeoutMillis;
        this.random = random;
        this.timers = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 입력 검증: 앞뒤 공백 제거, 1~max UTF-16 코드 단위, 제어문자 불가. 실패하면 null. */
    private static String clean(String raw, int max) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty() || s.length() > max) return null;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) return null;
        }
        return s;
    }

    private static void error(Session s, String code, String description) {
        s.send(Message.of("ERROR", code, description));
    }

    // ---- 접속 ----

    /** 성공하면 null. 실패하면 ERROR를 보내고 코드를 반환한다(연결 종료는 호출자). */
    synchronized String register(Session s, String rawNickname) {
        String nick = clean(rawNickname, 20);
        if (nick == null) {
            error(s, "INVALID_NICKNAME", "닉네임은 제어문자 없이 1~20자로 입력해 주세요");
            return "INVALID_NICKNAME";
        }
        if (users.containsKey(nick)) {
            error(s, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다");
            return "NICKNAME_TAKEN";
        }
        users.put(nick, s);
        s.nickname = nick;
        s.send(Message.of("WELCOME", nick));
        s.send(roomsMessage());
        System.out.println("[서버] 접속: " + nick);
        return null;
    }

    /** 접속 종료(정상·비정상 공통). 방 퇴장과 닉네임 해제를 처리한다. */
    synchronized void disconnect(Session s) {
        if (s.nickname == null || users.get(s.nickname) != s) return; // 등록되지 않은 연결
        users.remove(s.nickname);
        if (s.room != null) {
            removeFromRoom(s, false);
        }
        System.out.println("[서버] 종료: " + s.nickname);
        s.nickname = null;
    }

    // ---- 방 ----

    synchronized void sendRooms(Session s) {
        s.send(roomsMessage());
    }

    synchronized void createRoom(Session s, String rawName) {
        String name = clean(rawName, 40);
        if (name == null) {
            error(s, "INVALID_ROOM", "방 이름은 제어문자 없이 1~40자로 입력해 주세요");
            return;
        }
        if (s.room != null) {
            error(s, "INVALID_ROOM", "이미 방에 입장해 있습니다. 먼저 퇴장해 주세요");
            return;
        }
        Room room = new Room("r" + nextRoomNumber++, name);
        rooms.put(room.id, room);
        enter(s, room);
    }

    synchronized void joinRoom(Session s, String roomId) {
        if (s.room != null) {
            error(s, "INVALID_ROOM", "이미 방에 입장해 있습니다. 먼저 퇴장해 주세요");
            return;
        }
        Room room = roomId == null ? null : rooms.get(roomId);
        if (room == null) {
            error(s, "ROOM_NOT_FOUND", "존재하지 않는 방입니다");
            return;
        }
        enter(s, room);
    }

    synchronized void leaveRoom(Session s) {
        if (s.room == null) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        removeFromRoom(s, true);
    }

    /** 요청자: ROOM_JOINED → ROOM_USERS → SYSTEM. 기존 참가자: ROOM_USERS → SYSTEM. 이후 전체에 ROOMS. */
    private void enter(Session s, Room room) {
        room.members.add(s);
        s.room = room;
        s.send(Message.of("ROOM_JOINED", room.id, room.name));
        Message userList = usersMessage(room);
        for (Session member : room.members) member.send(userList);
        Message notice = Message.of("SYSTEM", room.id, s.nickname + "님이 입장했습니다");
        for (Session member : room.members) member.send(notice);
        broadcastRooms();
    }

    /** 요청자: ROOM_LEFT → ROOMS. 남은 참가자: SYSTEM → ROOM_USERS → ROOMS. */
    private void removeFromRoom(Session s, boolean notifySelf) {
        Room room = s.room;
        room.members.remove(s);
        s.room = null;
        leaveGame(room, s);
        if (notifySelf) s.send(Message.of("ROOM_LEFT"));
        if (room.members.isEmpty()) {
            rooms.remove(room.id);
        } else {
            Message notice = Message.of("SYSTEM", room.id, s.nickname + "님이 퇴장했습니다");
            Message userList = usersMessage(room);
            for (Session member : room.members) {
                member.send(notice);
                member.send(userList);
            }
        }
        broadcastRooms();
    }

    // ---- 채팅 ----

    synchronized void chat(Session s, String rawText) {
        if (s.room == null) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        String text = clean(rawText, 1000);
        if (text == null) {
            error(s, "INVALID_MESSAGE", "메시지는 제어문자 없이 1~1000자로 입력해 주세요");
            return;
        }
        Message message = Message.of("CHAT_MESSAGE", s.room.id, s.nickname, text);
        for (Session member : s.room.members) member.send(message); // 보낸 사람 포함
    }

    // ---- 가위바위보 ----

    synchronized void gameStart(Session s) {
        if (s.room == null) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        Room room = s.room;
        if (room.game != null) {
            error(s, "GAME_BUSY", "이미 게임이 진행 중입니다");
            return;
        }
        if (room.members.size() < 2) {
            error(s, "NOT_ENOUGH_PLAYERS", "참가자가 2명 이상 필요합니다");
            return;
        }
        String id = "g" + nextGameNumber++;
        Game game = new Game(id, new LinkedHashSet<>(room.members));
        room.game = game;
        game.timer = timers.schedule(() -> gameTimeout(room.id, id), choiceTimeoutMillis, TimeUnit.MILLISECONDS);

        List<String> fields = new ArrayList<>();
        fields.add(id);
        fields.add(s.nickname);
        fields.add(String.valueOf(game.participants.size()));
        for (Session member : game.participants) fields.add(member.nickname);
        Message started = new Message("GAME_STARTED", fields);
        for (Session member : room.members) member.send(started);
    }

    synchronized void gameChoose(Session s, String gameId, String choice) {
        Room room = s.room;
        Game game = room == null ? null : room.game;
        if (game == null || !Objects.equals(game.id, gameId) || !game.participants.contains(s)) {
            error(s, "GAME_NOT_FOUND", "참가 중인 게임을 찾을 수 없습니다");
            return;
        }
        if (choice == null || !CHOICES.contains(choice)) {
            error(s, "INVALID_CHOICE", "선택값은 ROCK/PAPER/SCISSORS 중 하나여야 합니다");
            return;
        }
        if (game.choices.containsKey(s)) {
            error(s, "ALREADY_CHOSEN", "이미 선택했습니다");
            return;
        }
        game.choices.put(s, choice);
        Message chosen = Message.of("GAME_CHOSEN", game.id, s.nickname);
        for (Session member : room.members) member.send(chosen);
        if (game.choices.size() == game.participants.size()) finishGame(room, game);
    }

    /** 참가자가 방을 나가거나 접속이 끊긴 뒤 호출. room.members는 이미 s가 빠진 상태여야 한다. */
    private void leaveGame(Room room, Session s) {
        Game game = room.game;
        if (game == null || !game.participants.remove(s)) return;
        game.choices.remove(s);
        if (room.members.isEmpty()) {
            cancelTimer(game);
            room.game = null;
            return;
        }
        if (game.participants.size() < 2) {
            cancelTimer(game);
            room.game = null;
            Message canceled = Message.of("GAME_CANCELED", game.id, "PLAYERS_LEFT");
            for (Session member : room.members) member.send(canceled);
        } else if (game.choices.size() == game.participants.size()) {
            finishGame(room, game);
        }
    }

    /** 타이머 만료. 이미 끝났거나 다른 게임으로 바뀐 경우 아무것도 하지 않는다. */
    private synchronized void gameTimeout(String roomId, String gameId) {
        Room room = rooms.get(roomId);
        if (room == null) return;
        Game game = room.game;
        if (game == null || !game.id.equals(gameId)) return;
        if (game.choices.size() < 2) {
            cancelTimer(game);
            room.game = null;
            Message canceled = Message.of("GAME_CANCELED", game.id, "TIMEOUT");
            for (Session member : room.members) member.send(canceled);
        } else {
            finishGame(room, game);
        }
    }

    /** 응답한 참가자만으로 판정하고 방 전체에 알린 뒤 승자에게 이모티콘을 지급한다. */
    private void finishGame(Room room, Game game) {
        cancelTimer(game);
        room.game = null;

        List<Session> responded = new ArrayList<>();
        for (Session p : game.participants) if (game.choices.containsKey(p)) responded.add(p);

        List<String> fields = new ArrayList<>();
        fields.add(game.id);
        fields.add(String.valueOf(responded.size()));
        for (Session p : responded) {
            fields.add(p.nickname);
            fields.add(game.choices.get(p));
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (Session p : responded) distinct.add(game.choices.get(p));
        String winningChoice = distinct.size() == 2 ? winningChoice(distinct) : null;
        List<Session> winners = new ArrayList<>();
        if (winningChoice != null) {
            for (Session p : responded) if (game.choices.get(p).equals(winningChoice)) winners.add(p);
        }
        fields.add(String.valueOf(winners.size()));
        for (Session w : winners) fields.add(w.nickname);

        Message result = new Message("GAME_RESULT", fields);
        for (Session member : room.members) member.send(result);
        for (Session winner : winners) grantEmoticon(winner, game.id);
    }

    /** 서로 다른 두 선택 중 이기는 쪽. */
    private static String winningChoice(Set<String> distinctChoices) {
        var it = distinctChoices.iterator();
        String a = it.next();
        String b = it.next();
        return BEATS.get(a).equals(b) ? a : b;
    }

    private void cancelTimer(Game game) {
        if (game.timer != null) game.timer.cancel(false);
    }

    // ---- 이모티콘 ----

    synchronized void emoticonList(Session s) {
        s.send(ownedMessage(s.nickname));
    }

    synchronized void emoticonSend(Session s, String id) {
        if (s.room == null) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        if (id == null || !EMOTICON_IDS.contains(id)) {
            error(s, "INVALID_EMOTICON", "존재하지 않는 이모티콘입니다");
            return;
        }
        if (!emoticons.getOrDefault(s.nickname, Set.of()).contains(id)) {
            error(s, "EMOTICON_NOT_OWNED", "보유하지 않은 이모티콘입니다");
            return;
        }
        Message message = Message.of("EMOTICON_MESSAGE", s.room.id, s.nickname, id);
        for (Session member : s.room.members) member.send(message); // 보낸 사람 포함
    }

    /** 승자에게 아직 없는 이모티콘 중 무작위 1개를 지급한다. 전부 보유 중이면 아무 일도 하지 않는다. */
    private void grantEmoticon(Session winner, String gameId) {
        Set<String> owned = emoticons.computeIfAbsent(winner.nickname, k -> new LinkedHashSet<>());
        List<String> missing = new ArrayList<>(EMOTICON_IDS);
        missing.removeAll(owned);
        if (missing.isEmpty()) return;
        String granted = missing.get(random.nextInt(missing.size()));
        owned.add(granted);
        winner.send(Message.of("EMOTICON_GRANTED", gameId, granted));
    }

    private Message ownedMessage(String nickname) {
        List<String> ids = new ArrayList<>(emoticons.getOrDefault(nickname, Set.of()));
        Collections.sort(ids);
        return new Message("EMOTICON_OWNED", ids);
    }

    // ---- 귓속말 ----

    synchronized void whisper(Session s, String target, String rawText) {
        if (s.room == null) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        String text = clean(rawText, 1000);
        if (text == null) {
            error(s, "INVALID_MESSAGE", "메시지는 제어문자 없이 1~1000자로 입력해 주세요");
            return;
        }
        Session targetSession = null;
        if (target != null && !target.equals(s.nickname)) {
            for (Session member : s.room.members) {
                if (member.nickname.equals(target)) {
                    targetSession = member;
                    break;
                }
            }
        }
        if (targetSession == null) {
            error(s, "INVALID_TARGET", "같은 방에 있는 상대를 선택해 주세요");
            return;
        }
        Message message = Message.of("WHISPER_MESSAGE", s.room.id, s.nickname, target, text);
        s.send(message);
        targetSession.send(message);
    }

    // ---- 메시지 생성·전송 ----

    private Message roomsMessage() {
        List<String> fields = new ArrayList<>();
        for (Room room : rooms.values()) {
            fields.add(room.id);
            fields.add(room.name);
            fields.add(String.valueOf(room.members.size()));
        }
        return new Message("ROOMS", fields);
    }

    private Message usersMessage(Room room) {
        List<String> fields = new ArrayList<>();
        fields.add(room.id);
        for (Session member : room.members) fields.add(member.nickname);
        return new Message("ROOM_USERS", fields);
    }

    private void broadcastRooms() {
        Message message = roomsMessage();
        for (Session user : users.values()) user.send(message);
    }
}
