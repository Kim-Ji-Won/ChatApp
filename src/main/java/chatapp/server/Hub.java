package chatapp.server;

import chatapp.common.Message;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
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
import java.util.UUID;
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
 * 정책(docs/protocol.md, docs/protocol-week3.md의 공동 확정 사항):
 *  - 한 사용자는 한 방에만 있을 수 있다. 방에 있는 상태의 ROOM_CREATE/ROOM_JOIN은 ERROR(INVALID_ROOM).
 *    (클라이언트는 ROOM_LEAVE → ROOM_LEFT 확인 후 다음 방을 요청한다.)
 *  - 참가자가 0명이 된 방도 삭제하지 않는다(3주차: 방의 할 일·일정을 보존하기 위해). 사용자가 직접
 *    방을 지우는 기능은 없다. 서버 재시작 시에는 모든 방·할 일·일정이 함께 초기화된다.
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
        final Map<String, Todo> todos = new LinkedHashMap<>();
        final Map<String, Schedule> schedules = new LinkedHashMap<>();
        /** 답장 대상 조회용 최근 일반 채팅·답장 사본(합계 최근 RECENT_MESSAGE_LIMIT개). */
        final LinkedHashMap<String, ChatRecord> recentMessages = new LinkedHashMap<>();

        Room(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 답장 대상 조회용 채팅 사본. 원문이 지워지는 일 없이 생성 시점 그대로 보관한다. */
    static final class ChatRecord {
        final String sender;
        final String text;
        final boolean reply;

        ChatRecord(String sender, String text, boolean reply) {
            this.sender = sender;
            this.text = text;
            this.reply = reply;
        }
    }

    /** 채팅 메시지에서 만드는 할 일. 원문(sourceSender·sourceText)은 생성 시점의 사본이며 이후 바뀌지 않는다. */
    static final class Todo {
        final String id;
        final String author;
        final String sourceSender;
        final String sourceText;
        long revision;
        String title;
        String assignee;
        String dueDate;
        String dueTime;
        boolean done;

        Todo(String id, String author, String sourceSender, String sourceText) {
            this.id = id;
            this.author = author;
            this.sourceSender = sourceSender;
            this.sourceText = sourceText;
        }

        List<String> fields() {
            return List.of(id, String.valueOf(revision), title, author, assignee, dueDate, dueTime,
                    done ? "1" : "0", sourceSender, sourceText);
        }
    }

    /** 방별 약속(일정). */
    static final class Schedule {
        final String id;
        final String author;
        long revision;
        String date;
        String title;
        String time;
        String memo;

        Schedule(String id, String author) {
            this.id = id;
            this.author = author;
        }

        List<String> fields() {
            return List.of(id, String.valueOf(revision), date, title, time, memo, author);
        }
    }

    /**
     * 세션별 requestId 중복 처리 기록. 같은 요청이 다시 오면 재실행 없이 이 응답을 다시 보낸다.
     * 메시지 종류도 같이 저장해야 한다 — 서로 다른 요청(예: TODO_DELETE와 SCHEDULE_DELETE)이
     * 우연히 같은 필드 값을 가질 수 있으므로, 필드만 비교하면 다른 종류의 요청을 같은 요청으로
     * 오인할 수 있다.
     */
    static final class RequestRecord {
        final String type;
        final List<String> requestFields;
        final String roomId;
        final Message response;

        RequestRecord(String type, List<String> requestFields, String roomId, Message response) {
            this.type = type;
            this.requestFields = requestFields;
            this.roomId = roomId;
            this.response = response;
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
    private int nextTodoNumber = 1;
    private int nextScheduleNumber = 1;
    /** 방별 최근 메시지·연결별 최근 답장 성공 요청 보관 한도(4주차 합의). */
    private static final int RECENT_MESSAGE_LIMIT = 200;
    private static final int REPLY_REQUEST_LIMIT = 200;

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

    /** title/room-name처럼 한 줄만 허용하는 기존 clean()과 달리, 줄바꿈(\n)만 허용하고 길이 하한을 받는다. */
    private static String cleanMultiline(String raw, int min, int max) {
        if (raw == null || raw.length() < min || raw.length() > max) return null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) && c != '\n') return null;
        }
        return raw;
    }

    private static boolean validDate(String date) {
        if (date.isEmpty()) return true;
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try { LocalDate.parse(date); return true; } catch (DateTimeException e) { return false; }
    }

    private static boolean validTime(String time) {
        if (time.isEmpty()) return true;
        if (!time.matches("\\d{2}:\\d{2}")) return false;
        try { LocalTime.parse(time); return true; } catch (DateTimeException e) { return false; }
    }

    /** 표준 형식(소문자, 하이픈 포함)의 UUID 문자열인지 확인한다. */
    private static boolean validUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** 가장 오래전에 넣은 항목부터 지워 한도를 맞춘다(LinkedHashMap의 삽입 순서를 이용). */
    private static void trimToLimit(LinkedHashMap<String, ?> entries, int limit) {
        while (entries.size() > limit) entries.remove(entries.keySet().iterator().next());
    }

    private static void error(Session s, String code, String description) {
        s.send(Message.of("ERROR", code, description));
    }

    /** 할 일·일정 생성·수정·완료·삭제 요청의 오류 응답(3필드: code, description, requestId). */
    private static void error3(Session s, String code, String description, String requestId) {
        s.send(Message.of("ERROR", code, description, requestId == null ? "" : requestId));
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
        if (!room.members.isEmpty()) {
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
        String messageId = UUID.randomUUID().toString();
        s.room.recentMessages.put(messageId, new ChatRecord(s.nickname, text, false));
        trimToLimit(s.room.recentMessages, RECENT_MESSAGE_LIMIT);
        Message message = Message.of("CHAT_MESSAGE", s.room.id, s.nickname, text, messageId);
        for (Session member : s.room.members) member.send(message); // 보낸 사람 포함
    }

    /**
     * 같은 방의 일반 텍스트 메시지에만 답장할 수 있다(귓속말·시스템·이모티콘·답장에 또 답장은 제외).
     * 원문 작성자·내용은 클라이언트가 보내지 않고, 방에 보관된 사본에서 서버가 채워 보낸다.
     */
    synchronized void chatReply(Session s, List<String> requestFields, String requestId, String roomId,
            String replyToMessageId, String rawText) {
        if (requestFields.size() != 4 || !validUuid(requestId)) {
            error3(s, "INVALID_REPLY", "답장 요청의 필드 수나 요청 번호 형식을 확인해 주세요", requestId);
            return;
        }
        if (replayReplyIfDuplicate(s, requestId, requestFields)) return;
        if (s.room == null || !s.room.id.equals(roomId)) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        if (!validUuid(replyToMessageId)) {
            error3(s, "INVALID_REPLY", "원문 번호 형식을 확인해 주세요", requestId);
            return;
        }
        String text = clean(rawText, 1000);
        if (text == null) {
            error3(s, "INVALID_MESSAGE", "메시지는 제어문자 없이 1~1000자로 입력해 주세요", requestId);
            return;
        }
        ChatRecord source = s.room.recentMessages.get(replyToMessageId);
        if (source == null) {
            error3(s, "REPLY_TARGET_NOT_FOUND", "원문을 찾을 수 없습니다", requestId);
            return;
        }
        if (source.reply) {
            error3(s, "INVALID_REPLY_TARGET", "일반 메시지에만 답장할 수 있습니다", requestId);
            return;
        }
        String messageId = UUID.randomUUID().toString();
        s.room.recentMessages.put(messageId, new ChatRecord(s.nickname, text, true));
        trimToLimit(s.room.recentMessages, RECENT_MESSAGE_LIMIT);
        Message reply = Message.of("CHAT_REPLY_MESSAGE", s.room.id, requestId, messageId, s.nickname, text,
                replyToMessageId, source.sender, source.text);
        for (Session member : s.room.members) member.send(reply); // 보낸 사람 포함
        rememberReply(s, requestId, requestFields, s.room.id, reply);
    }

    private boolean replayReplyIfDuplicate(Session s, String requestId, List<String> requestFields) {
        RequestRecord record = s.replyRequests.get(requestId);
        if (record == null) return false;
        if (!record.type.equals("CHAT_REPLY_SEND") || !record.requestFields.equals(requestFields)) {
            error3(s, "INVALID_REPLY", "같은 요청 번호를 다른 내용으로 다시 보냈습니다", requestId);
            return true;
        }
        if (s.room != null && s.room.id.equals(record.roomId)) {
            s.send(record.response);
        } else {
            error3(s, "NOT_IN_ROOM", "그 사이 방을 벗어나 이전 요청을 다시 처리할 수 없습니다", requestId);
        }
        return true;
    }

    private void rememberReply(Session s, String requestId, List<String> requestFields, String roomId, Message response) {
        s.replyRequests.put(requestId, new RequestRecord("CHAT_REPLY_SEND", requestFields, roomId, response));
        trimToLimit(s.replyRequests, REPLY_REQUEST_LIMIT);
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

    // ---- 할 일 ----

    synchronized void todoList(Session s, String roomId) {
        if (s.room == null || !s.room.id.equals(roomId)) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        List<Todo> items = new ArrayList<>(s.room.todos.values());
        s.send(Message.of("TODO_LIST_START", roomId, String.valueOf(items.size())));
        for (Todo t : items) s.send(new Message("TODO_ITEM", t.fields()));
        s.send(Message.of("TODO_LIST_END", roomId));
    }

    synchronized void todoCreate(Session s, List<String> requestFields, String requestId, String roomId,
            String rawTitle, String rawSourceSender, String rawSourceText, String rawAssignee, String rawDueDate, String rawDueTime) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_TODO", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 8) {
            error3(s, "INVALID_TODO", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "TODO_CREATE", requestId, requestFields, "INVALID_TODO")) return;
        if (s.room == null || !s.room.id.equals(roomId)) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        String title = clean(rawTitle, 80);
        String sourceSender = clean(rawSourceSender, 20);
        String sourceText = cleanMultiline(rawSourceText, 1, 1000);
        String assignee = rawAssignee == null ? "" : rawAssignee;
        String dueDate = rawDueDate == null ? "" : rawDueDate;
        String dueTime = rawDueTime == null ? "" : rawDueTime;
        boolean timeWithoutDate = !dueTime.isEmpty() && dueDate.isEmpty();
        if (title == null || sourceSender == null || sourceText == null || !validDate(dueDate) || !validTime(dueTime)
                || timeWithoutDate || (!assignee.isEmpty() && !isMember(s.room, assignee))) {
            error3(s, "INVALID_TODO", "입력값을 확인해 주세요", requestId);
            return;
        }
        Todo todo = new Todo("t" + nextTodoNumber++, s.nickname, sourceSender, sourceText);
        todo.revision = 1;
        todo.title = title;
        todo.assignee = assignee;
        todo.dueDate = dueDate;
        todo.dueTime = dueTime;
        todo.done = false;
        s.room.todos.put(todo.id, todo);
        Message created = todoNotice("TODO_CREATED", s.room.id, requestId, todo);
        for (Session member : s.room.members) member.send(created);
        remember(s, "TODO_CREATE", requestId, requestFields, s.room.id, created);
    }

    synchronized void todoEdit(Session s, List<String> requestFields, String requestId, String todoId, String expectedRevisionStr,
            String rawTitle, String rawAssignee, String rawDueDate, String rawDueTime) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_TODO", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 7) {
            error3(s, "INVALID_TODO", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "TODO_EDIT", requestId, requestFields, "INVALID_TODO")) return;
        if (s.room == null) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        Todo todo = s.room.todos.get(todoId);
        if (todo == null) {
            error3(s, "TODO_NOT_FOUND", "할 일을 찾을 수 없습니다", requestId);
            return;
        }
        if (!s.nickname.equals(todo.author)) {
            error3(s, "FORBIDDEN", "작성자만 수정할 수 있습니다", requestId);
            return;
        }
        Long expected = parseRevision(expectedRevisionStr);
        if (expected == null || expected != todo.revision) {
            error3(s, "CONFLICT", "최신 내용을 먼저 확인해 주세요", requestId);
            return;
        }
        String title = clean(rawTitle, 80);
        String assignee = rawAssignee == null ? "" : rawAssignee;
        String dueDate = rawDueDate == null ? "" : rawDueDate;
        String dueTime = rawDueTime == null ? "" : rawDueTime;
        boolean timeWithoutDate = !dueTime.isEmpty() && dueDate.isEmpty();
        boolean assigneeChanged = !assignee.equals(todo.assignee);
        if (title == null || !validDate(dueDate) || !validTime(dueTime) || timeWithoutDate
                || (assigneeChanged && !assignee.isEmpty() && !isMember(s.room, assignee))) {
            error3(s, "INVALID_TODO", "입력값을 확인해 주세요", requestId);
            return;
        }
        todo.title = title;
        todo.assignee = assignee;
        todo.dueDate = dueDate;
        todo.dueTime = dueTime;
        todo.revision++;
        Message edited = todoNotice("TODO_EDITED", s.room.id, requestId, todo);
        for (Session member : s.room.members) member.send(edited);
        remember(s, "TODO_EDIT", requestId, requestFields, s.room.id, edited);
    }

    synchronized void todoComplete(Session s, List<String> requestFields, String requestId, String todoId,
            String expectedRevisionStr, String doneStr) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_TODO", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 4) {
            error3(s, "INVALID_TODO", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "TODO_COMPLETE", requestId, requestFields, "INVALID_TODO")) return;
        if (s.room == null) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        Todo todo = s.room.todos.get(todoId);
        if (todo == null) {
            error3(s, "TODO_NOT_FOUND", "할 일을 찾을 수 없습니다", requestId);
            return;
        }
        if (!s.nickname.equals(todo.author) && !s.nickname.equals(todo.assignee)) {
            error3(s, "FORBIDDEN", "작성자 또는 담당자만 완료 상태를 바꿀 수 있습니다", requestId);
            return;
        }
        Long expected = parseRevision(expectedRevisionStr);
        if (expected == null || expected != todo.revision) {
            error3(s, "CONFLICT", "최신 내용을 먼저 확인해 주세요", requestId);
            return;
        }
        if (!"0".equals(doneStr) && !"1".equals(doneStr)) {
            error3(s, "INVALID_TODO", "완료 값은 0 또는 1이어야 합니다", requestId);
            return;
        }
        todo.done = "1".equals(doneStr);
        todo.revision++;
        Message completed = Message.of("TODO_COMPLETED", s.room.id, requestId, todo.id, String.valueOf(todo.revision), doneStr);
        for (Session member : s.room.members) member.send(completed);
        remember(s, "TODO_COMPLETE", requestId, requestFields, s.room.id, completed);
    }

    synchronized void todoDelete(Session s, List<String> requestFields, String requestId, String todoId, String expectedRevisionStr) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_TODO", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 3) {
            error3(s, "INVALID_TODO", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "TODO_DELETE", requestId, requestFields, "INVALID_TODO")) return;
        if (s.room == null) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        Todo todo = s.room.todos.get(todoId);
        if (todo == null) {
            error3(s, "TODO_NOT_FOUND", "할 일을 찾을 수 없습니다", requestId);
            return;
        }
        if (!s.nickname.equals(todo.author)) {
            error3(s, "FORBIDDEN", "작성자만 삭제할 수 있습니다", requestId);
            return;
        }
        Long expected = parseRevision(expectedRevisionStr);
        if (expected == null || expected != todo.revision) {
            error3(s, "CONFLICT", "최신 내용을 먼저 확인해 주세요", requestId);
            return;
        }
        s.room.todos.remove(todoId);
        Message deleted = Message.of("TODO_DELETED", s.room.id, requestId, todoId);
        for (Session member : s.room.members) member.send(deleted);
        remember(s, "TODO_DELETE", requestId, requestFields, s.room.id, deleted);
    }

    // ---- 일정 ----

    synchronized void scheduleList(Session s, String roomId, String rawDate) {
        if (s.room == null || !s.room.id.equals(roomId)) {
            error(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다");
            return;
        }
        String date = rawDate == null ? "" : rawDate;
        List<Schedule> items = new ArrayList<>();
        for (Schedule sc : s.room.schedules.values()) if (date.isEmpty() || date.equals(sc.date)) items.add(sc);
        s.send(Message.of("SCHEDULE_LIST_START", roomId, String.valueOf(items.size())));
        for (Schedule sc : items) s.send(new Message("SCHEDULE_ITEM", sc.fields()));
        s.send(Message.of("SCHEDULE_LIST_END", roomId));
    }

    synchronized void scheduleCreate(Session s, List<String> requestFields, String requestId, String roomId,
            String rawDate, String rawTitle, String rawTime, String rawMemo) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_SCHEDULE", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 6) {
            error3(s, "INVALID_SCHEDULE", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "SCHEDULE_CREATE", requestId, requestFields, "INVALID_SCHEDULE")) return;
        if (s.room == null || !s.room.id.equals(roomId)) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        String title = clean(rawTitle, 80);
        String date = rawDate == null ? "" : rawDate;
        String time = rawTime == null ? "" : rawTime;
        String memo = cleanMultiline(rawMemo == null ? "" : rawMemo, 0, 500);
        if (title == null || date.isEmpty() || !validDate(date) || !validTime(time) || memo == null) {
            error3(s, "INVALID_SCHEDULE", "입력값을 확인해 주세요", requestId);
            return;
        }
        Schedule schedule = new Schedule("d" + nextScheduleNumber++, s.nickname);
        schedule.revision = 1;
        schedule.date = date;
        schedule.title = title;
        schedule.time = time;
        schedule.memo = memo;
        s.room.schedules.put(schedule.id, schedule);
        Message created = scheduleNotice("SCHEDULE_CREATED", s.room.id, requestId, schedule);
        for (Session member : s.room.members) member.send(created);
        remember(s, "SCHEDULE_CREATE", requestId, requestFields, s.room.id, created);
    }

    synchronized void scheduleEdit(Session s, List<String> requestFields, String requestId, String scheduleId, String expectedRevisionStr,
            String rawDate, String rawTitle, String rawTime, String rawMemo) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_SCHEDULE", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 7) {
            error3(s, "INVALID_SCHEDULE", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "SCHEDULE_EDIT", requestId, requestFields, "INVALID_SCHEDULE")) return;
        if (s.room == null) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        Schedule schedule = s.room.schedules.get(scheduleId);
        if (schedule == null) {
            error3(s, "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다", requestId);
            return;
        }
        if (!s.nickname.equals(schedule.author)) {
            error3(s, "FORBIDDEN", "작성자만 수정할 수 있습니다", requestId);
            return;
        }
        Long expected = parseRevision(expectedRevisionStr);
        if (expected == null || expected != schedule.revision) {
            error3(s, "CONFLICT", "최신 내용을 먼저 확인해 주세요", requestId);
            return;
        }
        String title = clean(rawTitle, 80);
        String date = rawDate == null ? "" : rawDate;
        String time = rawTime == null ? "" : rawTime;
        String memo = cleanMultiline(rawMemo == null ? "" : rawMemo, 0, 500);
        if (title == null || date.isEmpty() || !validDate(date) || !validTime(time) || memo == null) {
            error3(s, "INVALID_SCHEDULE", "입력값을 확인해 주세요", requestId);
            return;
        }
        schedule.date = date;
        schedule.title = title;
        schedule.time = time;
        schedule.memo = memo;
        schedule.revision++;
        Message edited = scheduleNotice("SCHEDULE_EDITED", s.room.id, requestId, schedule);
        for (Session member : s.room.members) member.send(edited);
        remember(s, "SCHEDULE_EDIT", requestId, requestFields, s.room.id, edited);
    }

    synchronized void scheduleDelete(Session s, List<String> requestFields, String requestId, String scheduleId, String expectedRevisionStr) {
        if (requestId == null || requestId.isEmpty()) {
            error3(s, "INVALID_SCHEDULE", "requestId가 필요합니다", "");
            return;
        }
        if (requestFields.size() != 3) {
            error3(s, "INVALID_SCHEDULE", "요청 필드 수가 올바르지 않습니다", requestId);
            return;
        }
        if (replayIfDuplicate(s, "SCHEDULE_DELETE", requestId, requestFields, "INVALID_SCHEDULE")) return;
        if (s.room == null) {
            error3(s, "NOT_IN_ROOM", "방에 입장해 있지 않습니다", requestId);
            return;
        }
        Schedule schedule = s.room.schedules.get(scheduleId);
        if (schedule == null) {
            error3(s, "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다", requestId);
            return;
        }
        if (!s.nickname.equals(schedule.author)) {
            error3(s, "FORBIDDEN", "작성자만 삭제할 수 있습니다", requestId);
            return;
        }
        Long expected = parseRevision(expectedRevisionStr);
        if (expected == null || expected != schedule.revision) {
            error3(s, "CONFLICT", "최신 내용을 먼저 확인해 주세요", requestId);
            return;
        }
        s.room.schedules.remove(scheduleId);
        Message deleted = Message.of("SCHEDULE_DELETED", s.room.id, requestId, scheduleId);
        for (Session member : s.room.members) member.send(deleted);
        remember(s, "SCHEDULE_DELETE", requestId, requestFields, s.room.id, deleted);
    }

    // ---- 할 일·일정 공통 ----

    private static boolean isMember(Room room, String nickname) {
        for (Session member : room.members) if (member.nickname.equals(nickname)) return true;
        return false;
    }

    private static Long parseRevision(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static Message todoNotice(String type, String roomId, String requestId, Todo todo) {
        List<String> fields = new ArrayList<>();
        fields.add(roomId);
        fields.add(requestId);
        fields.addAll(todo.fields());
        return new Message(type, fields);
    }

    private static Message scheduleNotice(String type, String roomId, String requestId, Schedule schedule) {
        List<String> fields = new ArrayList<>();
        fields.add(roomId);
        fields.add(requestId);
        fields.addAll(schedule.fields());
        return new Message(type, fields);
    }

    /** 중복 요청이면 처리를 끝내고 true. 새 요청이면 false(호출자가 계속 처리한다). */
    private boolean replayIfDuplicate(Session s, String type, String requestId, List<String> requestFields, String invalidCode) {
        RequestRecord record = s.plannerRequests.get(requestId);
        if (record == null) return false;
        if (!record.type.equals(type) || !record.requestFields.equals(requestFields)) {
            error3(s, invalidCode, "같은 요청 ID를 다른 내용으로 다시 보냈습니다", requestId);
            return true;
        }
        if (s.room != null && s.room.id.equals(record.roomId)) {
            s.send(record.response);
        } else {
            error3(s, "FORBIDDEN", "그 사이 방을 벗어나 이전 요청을 다시 처리할 수 없습니다", requestId);
        }
        return true;
    }

    private void remember(Session s, String type, String requestId, List<String> requestFields, String roomId, Message response) {
        s.plannerRequests.put(requestId, new RequestRecord(type, requestFields, roomId, response));
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
