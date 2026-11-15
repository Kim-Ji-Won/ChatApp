package chatapp.client.network;

import chatapp.common.Message;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** UI 체험용 로컬 데이터. 가상 참가자와 정해진 결과를 사용하며 실제 서버 통신·영구 저장은 하지 않는다. */
public final class DemoTransport implements ChatTransport {
    private final Map<String, String> rooms = new LinkedHashMap<>();
    private Listener listener;
    private String nickname;
    private String currentRoom;
    private int nextId = 3;
    private boolean closed;
    private String peer1, peer2, gameId;
    private int round;
    private boolean submitted;
    private final Set<String> owned = new LinkedHashSet<>(List.of("E01", "E02"));
    private final ScheduledExecutorService animationPeer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chatapp-demo-peer"); thread.setDaemon(true); return thread;
    });
    private final List<ScheduledFuture<?>> delayed = new ArrayList<>();

    @Override public synchronized void connect(String host, int port, String nickname, Listener listener) throws IOException {
        if (closed) throw new IOException("데모가 종료되었습니다.");
        this.listener = listener;
        this.nickname = nickname;
        peer1 = nickname.equals("데모 친구") ? "데모 친구 A" : "데모 친구";
        peer2 = nickname.equals("데모 친구 B") ? "데모 친구 C" : "데모 친구 B";
        rooms.put("1", "우리들의 아지트");
        rooms.put("2", "오늘 뭐 먹지?");
        emit(Message.of("WELCOME", nickname));
        roomList();
    }
    @Override public synchronized void send(Message message) throws IOException {
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
                cancelGame(); currentRoom = null;
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
            case "GAME_START" -> {
                message.requireFields(0);
                if (!"1".equals(currentRoom)) { error("NOT_ENOUGH_PLAYERS", "우리들의 아지트에서 가상 친구와 체험해 보세요."); break; }
                if (gameId != null) { error("GAME_IN_PROGRESS", "게임이 진행 중이에요."); break; }
                gameId = "demo-game-" + (++round); submitted=false;
                emit(Message.of("GAME_STARTED",gameId,nickname,"3",nickname,peer1,peer2));
                later(30000, () -> { emit(Message.of("GAME_CANCELED",gameId,"TIMEOUT")); cancelGame(); });
            }
            case "GAME_CHOOSE" -> {
                message.requireFields(2);
                if (!Objects.equals(gameId,message.field(0)) || gameId == null || submitted) { error("INVALID_GAME", "진행 중인 게임을 확인해 주세요."); break; }
                String move=message.field(1);
                if(!List.of("ROCK","PAPER","SCISSORS").contains(move)) { error("INVALID_CHOICE", "선택을 확인해 주세요."); break; }
                submitted=true; emit(Message.of("GAME_CHOSEN",gameId,nickname));
                later(700, () -> emit(Message.of("GAME_CHOSEN",gameId,peer1)));
                later(1400, () -> {
                    emit(Message.of("GAME_CHOSEN",gameId,peer2));
                    // 체험 순서: 승리 → 무승부 → 가상 친구 둘의 승리. 실제 판정은 서버 담당.
                    int scenario=(round-1)%3;
                    String opponent=scenario==1 ? move : switch(move) {
                        case "ROCK" -> scenario==0 ? "SCISSORS" : "PAPER";
                        case "PAPER" -> scenario==0 ? "ROCK" : "SCISSORS";
                        default -> scenario==0 ? "PAPER" : "ROCK";
                    };
                    List<String> result=new ArrayList<>(List.of(gameId,"3",nickname,move,peer1,opponent,peer2,opponent));
                    result.add(scenario==0 ? "1" : scenario==1 ? "0" : "2");
                    if(scenario==0) result.add(nickname);
                    if(scenario==2) { result.add(peer1); result.add(peer2); }
                    emit(new Message("GAME_RESULT",result));
                    if(scenario==0) for(int i=3;i<=8;i++) {
                        String reward="E0"+i;
                        if(owned.add(reward)) { emit(Message.of("EMOTICON_GRANTED",gameId,reward)); break; }
                    }
                    cancelGame();
                });
            }
            case "EMOTICON_LIST" -> { message.requireFields(0); emit(new Message("EMOTICON_OWNED",new ArrayList<>(owned))); }
            case "EMOTICON_SEND" -> {
                message.requireFields(1);
                if(currentRoom==null || !owned.contains(message.field(0))) { error("INVALID_EMOTICON", "방과 보유 이모티콘을 확인해 주세요."); break; }
                emit(Message.of("EMOTICON_MESSAGE",currentRoom,nickname,message.field(0)));
            }
            case "WHISPER_SEND" -> {
                message.requireFields(2);
                if(!"1".equals(currentRoom) || !List.of(peer1,peer2).contains(message.field(0))) { error("USER_NOT_FOUND", "같은 방의 가상 친구를 선택해 주세요."); break; }
                String body=message.field(1).strip();
                if(body.isEmpty() || body.length()>1000) { error("INVALID_MESSAGE", "메시지는 1~1000자로 입력해 주세요."); break; }
                emit(Message.of("WHISPER_MESSAGE",currentRoom,nickname,message.field(0),body));
                emit(Message.of("WHISPER_MESSAGE",currentRoom,message.field(0),nickname,"[데모 자동 응답] 잘 받았어요!"));
            }
            default -> error("UNSUPPORTED", "아직 구현되지 않은 요청입니다.");
        }
    }
    private void join(String id) {
        if (!rooms.containsKey(id)) { error("ROOM_NOT_FOUND", "방이 없습니다."); return; }
        cancelGame(); currentRoom = id;
        emit(Message.of("ROOM_JOINED", id, rooms.get(id)));
        emit("1".equals(id) ? Message.of("ROOM_USERS",id,nickname,peer1,peer2) : Message.of("ROOM_USERS", id, nickname));
        emit(Message.of("SYSTEM", id, "1".equals(id) ? "가상 친구와 게임·이모티콘·귓속말을 체험해 보세요. 게임 결과는 체험용 순서로 표시돼요." : "데모 방입니다. 메시지는 이 컴퓨터의 화면에만 표시됩니다."));
        roomList();
    }
    private void roomList() {
        List<String> fields = new ArrayList<>();
        rooms.forEach((id, name) -> { fields.add(id); fields.add(name); fields.add(String.valueOf((id.equals(currentRoom) ? 1 : 0) + (id.equals("1") ? 2 : 0))); });
        emit(new Message("ROOMS", fields));
    }
    private void error(String code, String text) { emit(Message.of("ERROR", code, text)); }
    private void emit(Message message) { listener.onMessage(message); }
    private void later(long millis, Runnable action) {
        String expectedGame=gameId, expectedRoom=currentRoom;
        delayed.add(animationPeer.schedule(() -> {
            synchronized (DemoTransport.this) {
                if(!closed && Objects.equals(gameId,expectedGame) && Objects.equals(currentRoom,expectedRoom)) action.run();
            }
        },millis,TimeUnit.MILLISECONDS));
    }
    private void cancelGame() {
        gameId=null; submitted=false;
        delayed.forEach(job -> job.cancel(false)); delayed.clear();
    }
    @Override public synchronized void close() { closed = true; cancelGame(); animationPeer.shutdownNow(); }
}
