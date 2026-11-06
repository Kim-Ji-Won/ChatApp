package chatapp.client.network;

import chatapp.common.Message;
import java.io.*;
import java.net.*;

/** 서버 연결·쓰기 작업은 UI가 아닌 작업 스레드에서 호출한다. 읽기는 전용 스레드. */
public final class SocketTransport implements ChatTransport {
    private volatile Socket socket;
    private volatile boolean closed;
    private DataOutputStream output;

    @Override public void connect(String host, int port, String nickname, Listener listener) throws IOException {
        Socket next = new Socket();
        socket = next;
        if (closed) { next.close(); throw new IOException("접속이 취소되었습니다."); }
        try {
            next.connect(new InetSocketAddress(host, port), 5000);
            next.setSoTimeout(5000);
            next.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(next.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(next.getOutputStream()));
            send(Message.of("HELLO", nickname));
            Message welcome = Message.read(input);
            if (welcome.type().equals("ERROR")) {
                welcome.requireFields(2);
                throw new IOException(welcome.field(1));
            }
            if (!welcome.type().equals("WELCOME")) throw new IOException("서버의 WELCOME 응답이 필요합니다.");
            welcome.requireFields(1);
            next.setSoTimeout(0);
            listener.onMessage(welcome);
            Thread reader = new Thread(() -> {
                try {
                    while (!closed) listener.onMessage(Message.read(input));
                } catch (IOException | RuntimeException e) {
                    if (!closed) listener.onDisconnected("서버 연결이 종료되었습니다. 다시 접속해 주세요.");
                } finally { close(); }
            }, "chatapp-socket-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override public synchronized void send(Message message) throws IOException {
        if (closed || output == null) throw new IOException("서버에 연결되어 있지 않습니다.");
        message.write(output);
    }
    @Override public void close() {
        closed = true;
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}
