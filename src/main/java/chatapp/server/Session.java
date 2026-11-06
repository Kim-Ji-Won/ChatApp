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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 클라이언트 연결 하나를 담당한다. 읽기는 이 스레드, 쓰기는 연결별 쓰기 스레드가 맡는다.
 *
 * 전송({@link #send})은 대기열에 넣기만 하고 바로 돌아온다. Hub의 공통 락 안에서 호출해도
 * 소켓 쓰기를 기다리지 않으므로, 메시지를 읽지 않는 연결 하나가 다른 방의 처리를 막지 못한다.
 * 대기열이 가득 찬(수신이 계속 밀리는) 연결은 서버가 끊는다. 연결별 순서는 대기열(FIFO)이 보장한다.
 */
final class Session implements Runnable {
    private static final int HELLO_TIMEOUT_MS = 5_000;
    /** 연결 하나에 쌓아둘 수 있는 전송 대기 메시지 수. 넘치면 연결을 끊는다. */
    private static final int OUTBOX_LIMIT = 256;
    private static final int FLUSH_WAIT_MS = 2_000;
    /** 대기열에 넣어 "앞의 메시지를 모두 보낸 뒤 연결을 닫아라"를 알리는 표식. */
    private static final Message CLOSE = new Message("CLOSE", List.of());

    private final Socket socket;
    private final Hub hub;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<Message> outbox = new LinkedBlockingQueue<>(OUTBOX_LIMIT);
    private final Thread writer;
    private volatile boolean closing;

    // 아래 두 필드는 Hub의 락 안에서만 변경한다.
    String nickname;
    Hub.Room room;

    Session(Socket socket, Hub hub) throws IOException {
        this.socket = socket;
        this.hub = hub;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        socket.setTcpNoDelay(true);
        this.writer = new Thread(this::writeLoop, "writer-" + socket.getPort());
        this.writer.setDaemon(true);
    }

    /** 전송 대기열에 넣는다. 기다리지 않는다. 대기열이 가득 차면 느린 연결로 보고 끊는다. */
    void send(Message message) {
        if (closing) return;
        if (!outbox.offer(message)) {
            System.out.println("[서버] 전송 대기열 초과, 연결 종료: 포트 " + socket.getPort());
            close();
        }
    }

    void close() {
        if (closing) return;
        closing = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
        writer.interrupt();
    }

    private void writeLoop() {
        try {
            while (true) {
                Message message = outbox.take();
                if (message == CLOSE) break;
                message.write(out);
            }
        } catch (IOException | InterruptedException e) {
            // 연결 종료
        } finally {
            close();
        }
    }

    /** 대기 중인 메시지(예: ERROR)를 모두 보낸 뒤 연결을 닫는다. */
    private void flushAndClose() {
        if (outbox.offer(CLOSE)) {
            try {
                writer.join(FLUSH_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 없는 필드는 null. (공통 Message.field는 범위를 벗어나면 예외를 던진다.) */
    private static String arg(Message message, int index) {
        return index < message.fields().size() ? message.fields().get(index) : null;
    }

    @Override
    public void run() {
        writer.start();
        try {
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            if (!handshake()) {
                flushAndClose();
                return;
            }
            socket.setSoTimeout(0);
            while (true) {
                dispatch(Message.read(in));
            }
        } catch (SocketTimeoutException e) {
            if (!closing) System.out.println("[서버] HELLO 시간 초과, 연결 종료");
        } catch (EOFException e) {
            // 정상 종료
        } catch (IOException e) {
            if (!closing) System.out.println("[서버] 연결 오류: " + e.getMessage());
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
