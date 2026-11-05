package chatapp.client.network;

import chatapp.common.Message;
import java.io.IOException;

/** 화면은 이 인터페이스만 사용한다. 데모와 TCP 구현을 교체할 수 있다. */
public interface ChatTransport extends AutoCloseable {
    interface Listener {
        void onMessage(Message message);
        void onDisconnected(String reason);
    }
    void connect(String host, int port, String nickname, Listener listener) throws IOException;
    void send(Message message) throws IOException;
    @Override void close();
}
