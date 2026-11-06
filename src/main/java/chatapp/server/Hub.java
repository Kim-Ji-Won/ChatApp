package chatapp.server;

import chatapp.common.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    static final class Room {
        final String id;
        final String name;
        final Set<Session> members = new LinkedHashSet<>();

        Room(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Map<String, Session> users = new HashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private int nextRoomNumber = 1;

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
