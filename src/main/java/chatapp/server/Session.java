package chatapp.server;

import chatapp.common.Message;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** 클라이언트 연결 하나를 담당하는 스레드. 닉네임·현재 방은 서버가 연결 기준으로 보관한다. */
final class Session implements Runnable {
    private static final int HELLO_TIMEOUT_MS = 5_000;

    private final Socket socket;
    private final Hub hub;
    private final DataInputStream in;
    private final DataOutputStream out;

    // 아래 두 필드는 Hub의 락 안에서만 변경한다.
    String nickname;
    Hub.Room room;

    Session(Socket socket, Hub hub) throws IOException {
        this.socket = socket;
        this.hub = hub;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        socket.setTcpNoDelay(true);
    }

    /** 다른 스레드(방 브로드캐스트)에서도 호출되므로 쓰기를 직렬화한다. */
    void send(Message message) {
        synchronized (out) {
            try {
                message.write(out);
            } catch (IOException e) {
                close(); // 수신 루프가 정리한다.
            }
        }
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    /** 없는 필드는 null. (공통 Message.field는 범위를 벗어나면 예외를 던진다.) */
    private static String arg(Message message, int index) {
        return index < message.fields().size() ? message.fields().get(index) : null;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            if (!handshake()) return;
            socket.setSoTimeout(0);
            while (true) {
                dispatch(Message.read(in));
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[서버] HELLO 시간 초과, 연결 종료");
        } catch (EOFException e) {
            // 정상 종료
        } catch (IOException e) {
            System.out.println("[서버] 연결 오류: " + e.getMessage());
        } finally {
            hub.disconnect(this);
            close();
        }
    }

    /** HELLO 처리. 실패하면 ERROR를 보낸 뒤 false. */
    private boolean handshake() throws IOException {
        Message first = Message.read(in);
        if (!first.type().equals("HELLO")) {
            send(Message.of("ERROR", "UNSUPPORTED", "먼저 HELLO가 필요합니다"));
            return false;
        }
        return hub.register(this, arg(first, 0)) == null;
    }

    private void dispatch(Message message) {
        switch (message.type()) {
            case "ROOM_LIST" -> hub.sendRooms(this);
            case "ROOM_CREATE" -> hub.createRoom(this, arg(message, 0));
            case "ROOM_JOIN" -> hub.joinRoom(this, arg(message, 0));
            case "ROOM_LEAVE" -> hub.leaveRoom(this);
            case "CHAT_SEND" -> hub.chat(this, arg(message, 0));
            default -> send(Message.of("ERROR", "UNSUPPORTED", "지원하지 않는 요청: " + message.type()));
        }
    }
}
