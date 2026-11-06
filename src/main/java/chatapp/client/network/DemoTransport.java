package chatapp.client.network;

import chatapp.common.Message;
import java.io.IOException;
import java.util.*;

/** UI 개발용 로컬 데이터. 서버, 다른 사용자, 영구 저장 기능은 제공하지 않는다. */
public final class DemoTransport implements ChatTransport {
    private final Map<String, String> rooms = new LinkedHashMap<>();
    private Listener listener;
    private String nickname;
    private String currentRoom;
    private int nextId = 3;
    private boolean closed;

    @Override public void connect(String host, int port, String nickname, Listener listener) throws IOException {
        if (closed) throw new IOException("데모가 종료되었습니다.");
        this.listener = listener;
        this.nickname = nickname;
        rooms.put("1", "우리들의 아지트");
        rooms.put("2", "오늘 뭐 먹지?");
        emit(Message.of("WELCOME", nickname));
        roomList();
    }
    @Override public void send(Message message) throws IOException {
        if (closed || listener == null) throw new IOException("데모에 연결되어 있지 않습니다.");
        switch (message.type()) {
            case "ROOM_LIST" -> roomList();
            case "ROOM_CREATE" -> {
                message.requireFields(1);
                String name = message.field(0).strip();
                if (name.isEmpty() || name.length() > 40) { error("INVALID_ROOM", "방 이름은 1~40자로 입력해 주세요."); break; }
                String id = String.valueOf(nextId++);
                rooms.put(id, name);
                join(id);
            }
            case "ROOM_JOIN" -> { message.requireFields(1); join(message.field(0)); }
            case "ROOM_LEAVE" -> {
                currentRoom = null;
                emit(Message.of("ROOM_LEFT"));
                roomList();
            }
            case "CHAT_SEND" -> {
                message.requireFields(1);
                if (currentRoom == null) { error("NOT_IN_ROOM", "먼저 방에 입장해 주세요."); break; }
                String body = message.field(0).strip();
                if (body.isEmpty() || body.length() > 1000) { error("INVALID_MESSAGE", "메시지는 1~1000자로 입력해 주세요."); break; }
                emit(Message.of("CHAT_MESSAGE", currentRoom, nickname, body));
            }
            default -> error("UNSUPPORTED", "아직 구현되지 않은 요청입니다.");
        }
    }
    private void join(String id) {
        if (!rooms.containsKey(id)) { error("ROOM_NOT_FOUND", "방이 없습니다."); return; }
        currentRoom = id;
        emit(Message.of("ROOM_JOINED", id, rooms.get(id)));
        emit(Message.of("ROOM_USERS", id, nickname));
        emit(Message.of("SYSTEM", id, "데모 방입니다. 메시지는 이 컴퓨터의 화면에만 표시됩니다."));
        roomList();
    }
    private void roomList() {
        List<String> fields = new ArrayList<>();
        rooms.forEach((id, name) -> { fields.add(id); fields.add(name); fields.add(id.equals(currentRoom) ? "1" : "0"); });
        emit(new Message("ROOMS", fields));
    }
    private void error(String code, String text) { emit(Message.of("ERROR", code, text)); }
    private void emit(Message message) { listener.onMessage(message); }
    @Override public void close() { closed = true; }
}
