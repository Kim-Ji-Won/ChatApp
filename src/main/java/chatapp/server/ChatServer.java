package chatapp.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/** 채팅 서버. 연결마다 스레드를 하나씩 둔다. 실행: ChatServer [port] (기본 5000) */
public final class ChatServer {
    private final ServerSocket serverSocket;
    private final Hub hub = new Hub();

    public ChatServer(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void serve() {
        System.out.println("[서버] 포트 " + port() + "에서 대기 중");
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread thread = new Thread(new Session(socket, hub), "client-" + socket.getPort());
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.out.println("[서버] accept 오류: " + e.getMessage());
                }
            }
        }
    }

    public void stop() throws IOException {
        serverSocket.close();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        new ChatServer(port).serve();
    }
}
