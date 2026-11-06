package chatapp;

import chatapp.common.Message;
import chatapp.server.ChatServer;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 1주차 서버 검증: 클라이언트 3개 접속, 방 2개 메시지 분리, 퇴장·접속 종료 반영. */
public final class ServerChecks {
    private static int passed = 0;

    private static final class Client {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Client(int port) throws Exception {
            this(port, 0);
        }

        /** receiveBuffer > 0이면 연결 전에 수신 버퍼를 작게 설정한다(느린 수신자 재현용). */
        Client(int port, int receiveBuffer) throws Exception {
            socket = new Socket();
            if (receiveBuffer > 0) socket.setReceiveBufferSize(receiveBuffer);
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.setSoTimeout(2000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        void send(String type, String... fields) throws Exception {
            Message.of(type, fields).write(out);
        }

        Message expect(String type) throws Exception {
            Message message = Message.read(in);
            if (!message.type().equals(type)) throw new AssertionError(type + " 기대, 실제 " + message);
            return message;
        }

        void expectError(String code, String name) throws Exception {
            check(expect("ERROR").field(0).equals(code), name);
        }

        void expectSilence() throws Exception {
            socket.setSoTimeout(300);
            try {
                throw new AssertionError("수신되면 안 되는 메시지: " + Message.read(in));
            } catch (SocketTimeoutException expected) {
                // 정상
            } finally {
                socket.setSoTimeout(2000);
            }
        }

        void expectClosed() throws Exception {
            try {
                throw new AssertionError("연결이 종료되어야 함, 수신: " + Message.read(in));
            } catch (EOFException expected) {
                // 정상
            }
        }

        void close() throws Exception {
            socket.close();
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("실패: " + name);
        passed++;
        System.out.println("  OK  " + name);
    }

    public static void main(String[] args) throws Exception {
        ChatServer server = new ChatServer(0);
        Thread serverThread = new Thread(server::serve);
        serverThread.setDaemon(true);
        serverThread.start();
        int port = server.port();

        // 프레임 형식: ROOM_JOIN r1 → 길이 14 + "ROOM_JOIN\tcjE="
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Message.of("ROOM_JOIN", "r1").write(new DataOutputStream(bytes));
        byte[] frame = bytes.toByteArray();
        check(frame.length == 18 && frame[3] == 14
                && new String(frame, 4, 14, StandardCharsets.UTF_8).equals("ROOM_JOIN\tcjE="), "프레임 예시(14바이트)");
        bytes.reset();
        Message.of("X", "a", "").write(new DataOutputStream(bytes));
        Message echoed = Message.read(new DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())));
        check(echoed.fields().equals(List.of("a", "")), "마지막 빈 필드 보존");

        Client a = new Client(port);
        Client b = new Client(port);
        Client c = new Client(port);

        // 접속: WELCOME → ROOMS
        a.send("HELLO", "정윤");
        check(a.expect("WELCOME").field(0).equals("정윤"), "WELCOME");
        check(a.expect("ROOMS").fields().isEmpty(), "방이 없으면 ROOMS 필드 0개");
        b.send("HELLO", "지원");
        b.expect("WELCOME");
        b.expect("ROOMS");

        // 닉네임 오류: ERROR 후 연결 종료
        Client duplicate = new Client(port);
        duplicate.send("HELLO", " 정윤 ");
        duplicate.expectError("NICKNAME_TAKEN", "닉네임 중복 거부(공백 제거 후 비교)");
        duplicate.expectClosed();
        Client invalid = new Client(port);
        invalid.send("HELLO", "");
        invalid.expectError("INVALID_NICKNAME", "빈 닉네임 거부");
        invalid.expectClosed();
        Client early = new Client(port);
        early.send("CHAT_SEND", "hello");
        early.expectError("UNSUPPORTED", "HELLO 전 요청 거부");
        early.expectClosed();

        // A가 방1 생성: ROOM_JOINED → ROOM_USERS → SYSTEM → ROOMS
        a.send("ROOM_CREATE", "방1");
        String room1 = a.expect("ROOM_JOINED").field(0);
        check(a.expect("ROOM_USERS").fields().equals(List.of(room1, "정윤")), "방 생성 후 ROOM_USERS");
        a.expect("SYSTEM");
        check(a.expect("ROOMS").fields().equals(List.of(room1, "방1", "1")), "요청자에게도 ROOMS 갱신");
        check(b.expect("ROOMS").fields().equals(List.of(room1, "방1", "1")), "다른 접속자 ROOMS 갱신(인원 1)");

        // B 입장
        b.send("ROOM_JOIN", room1);
        b.expect("ROOM_JOINED");
        check(b.expect("ROOM_USERS").fields().equals(List.of(room1, "정윤", "지원")), "입장자 ROOM_USERS");
        b.expect("SYSTEM");
        b.expect("ROOMS");
        check(a.expect("ROOM_USERS").fields().size() == 3, "기존 참가자 ROOM_USERS 갱신");
        a.expect("SYSTEM");
        check(a.expect("ROOMS").fields().get(2).equals("2"), "인원수 2 반영");

        // C 접속 후 방2 생성
        c.send("HELLO", "민수");
        c.expect("WELCOME");
        check(c.expect("ROOMS").fields().equals(List.of(room1, "방1", "2")), "접속 시 ROOMS 스냅샷");
        c.send("ROOM_CREATE", "방2");
        String room2 = c.expect("ROOM_JOINED").field(0);
        c.expect("ROOM_USERS");
        c.expect("SYSTEM");
        check(c.expect("ROOMS").fields().size() == 6, "방 2개 ROOMS");
        check(!room1.equals(room2), "방 ID 구분");
        check(a.expect("ROOMS").fields().size() == 6 && b.expect("ROOMS").fields().size() == 6, "방 안의 사용자도 ROOMS 수신");

        // 방별 채팅 분리(보낸 사람 포함)
        a.send("CHAT_SEND", "안녕하세요 😀");
        Message fromA = a.expect("CHAT_MESSAGE");
        check(fromA.fields().equals(List.of(room1, "정윤", "안녕하세요 😀")), "보낸 사람 포함 수신(한글·이모지)");
        check(b.expect("CHAT_MESSAGE").fields().equals(fromA.fields()), "같은 방 수신");
        c.expectSilence();
        check(true, "다른 방에는 전달되지 않음");
        c.send("CHAT_SEND", "방2 메시지");
        c.expect("CHAT_MESSAGE");
        a.expectSilence();
        b.expectSilence();
        check(true, "방2 메시지가 방1로 새지 않음");

        // 검증 실패: ERROR만 받고 상태는 그대로
        a.send("CHAT_SEND", "   ");
        a.expectError("INVALID_MESSAGE", "빈 메시지 거부");
        a.send("ROOM_JOIN", room2);
        a.expectError("INVALID_ROOM", "방에 있을 때 입장 거부");
        a.send("ROOM_CREATE", "방3");
        a.expectError("INVALID_ROOM", "방에 있을 때 생성 거부");
        Client guest = new Client(port);
        guest.send("HELLO", "게스트");
        guest.expect("WELCOME");
        guest.expect("ROOMS");
        guest.send("CHAT_SEND", "방 없음");
        guest.expectError("NOT_IN_ROOM", "방 밖 채팅 거부");
        guest.send("ROOM_LEAVE");
        guest.expectError("NOT_IN_ROOM", "방 밖 퇴장 거부");
        guest.send("ROOM_JOIN", "r999");
        guest.expectError("ROOM_NOT_FOUND", "없는 방 입장 거부");
        guest.send("BOGUS");
        guest.expectError("UNSUPPORTED", "알 수 없는 요청");
        guest.send("ROOM_LIST");
        check(guest.expect("ROOMS").fields().size() == 6, "ROOM_LIST 응답");
        guest.close();
        a.expectSilence();

        // B 퇴장: ROOM_LEFT → ROOMS, 남은 참가자 SYSTEM → ROOM_USERS
        b.send("ROOM_LEAVE");
        b.expect("ROOM_LEFT");
        check(b.expect("ROOMS").fields().equals(List.of(room1, "방1", "1", room2, "방2", "1")), "퇴장 후 ROOMS");
        a.expect("SYSTEM");
        check(a.expect("ROOM_USERS").fields().equals(List.of(room1, "정윤")), "퇴장 후 ROOM_USERS");
        a.expect("ROOMS");
        c.expect("ROOMS");

        // 방 전환(LEAVE 후 JOIN)
        b.send("ROOM_JOIN", room2);
        b.expect("ROOM_JOINED");
        check(b.expect("ROOM_USERS").fields().equals(List.of(room2, "민수", "지원")), "방 전환 후 ROOM_USERS");
        b.expect("SYSTEM");
        b.expect("ROOMS");
        c.expect("ROOM_USERS");
        c.expect("SYSTEM");
        c.expect("ROOMS");
        a.expect("ROOMS");

        // A 비정상 종료: 방1 삭제, 나머지에게 ROOMS
        a.close();
        List<String> afterClose = List.of(room2, "방2", "2");
        check(b.expect("ROOMS").fields().equals(afterClose), "접속 종료 반영(빈 방 삭제)");
        check(c.expect("ROOMS").fields().equals(afterClose), "방 안의 사용자에게도 반영");

        // C 종료: 남은 B에게 SYSTEM → ROOM_USERS → ROOMS
        c.close();
        b.expect("SYSTEM");
        check(b.expect("ROOM_USERS").fields().equals(List.of(room2, "지원")), "접속 종료 후 ROOM_USERS");
        b.expect("ROOMS");

        // 닉네임 재사용
        Client again = new Client(port);
        again.send("HELLO", "정윤");
        check(again.expect("WELCOME").field(0).equals("정윤"), "접속 종료 후 닉네임 재사용");

        slowClientCheck(port);

        System.out.println("\n통과 " + passed + "건");
        server.stop();
    }

    private static int roomCount(Message rooms, String name) {
        List<String> f = rooms.fields();
        for (int i = 0; i + 2 < f.size(); i += 3) {
            if (f.get(i + 1).equals(name)) return Integer.parseInt(f.get(i + 2));
        }
        return -1;
    }

    /** 메시지를 읽지 않는 클라이언트가 있어도 다른 방의 요청 처리가 멈추지 않아야 한다. */
    private static void slowClientCheck(int port) throws Exception {
        Client slow = new Client(port, 1024);
        slow.send("HELLO", "느린사람"); // 이후 수신 메시지를 전혀 읽지 않는다.
        slow.send("ROOM_CREATE", "느린방");
        Thread.sleep(300);

        Client flooder = new Client(port);
        flooder.send("HELLO", "발신자");
        flooder.expect("WELCOME");
        List<String> listed = flooder.expect("ROOMS").fields();
        String slowRoomId = null;
        for (int i = 0; i + 2 < listed.size(); i += 3) {
            if (listed.get(i + 1).equals("느린방")) slowRoomId = listed.get(i);
        }
        check(slowRoomId != null, "느린 클라이언트 방 확인");
        flooder.send("ROOM_JOIN", slowRoomId);
        flooder.expect("ROOM_JOINED");
        flooder.socket.setSoTimeout(0);
        AtomicInteger lastSeq = new AtomicInteger(-1);
        AtomicBoolean ordered = new AtomicBoolean(true);
        Thread drain = new Thread(() -> { // 발신자 본인의 수신 메시지를 비우며 도착 순서를 기록한다.
            try {
                while (true) {
                    Message received = Message.read(flooder.in);
                    if (!received.type().equals("CHAT_MESSAGE")) continue;
                    int seq = Integer.parseInt(received.field(2).substring(0, received.field(2).indexOf(':')));
                    if (seq != lastSeq.get() + 1) ordered.set(false);
                    lastSeq.set(seq);
                }
            } catch (Exception ignored) {
                // 종료
            }
        });
        drain.setDaemon(true);
        drain.start();

        Client other = new Client(port);
        other.send("HELLO", "관찰자");
        other.expect("WELCOME");
        other.expect("ROOMS");
        other.send("ROOM_CREATE", "다른방");
        other.expect("ROOM_JOINED");
        other.expect("ROOM_USERS");
        other.expect("SYSTEM");
        other.expect("ROOMS");

        AtomicBoolean done = new AtomicBoolean(false);
        Thread flood = new Thread(() -> {
            String padding = "x".repeat(900);
            try {
                for (int i = 0; i < 20_000; i++) {
                    flooder.send("CHAT_SEND", i + ":" + padding);
                    if (i % 20 == 19) Thread.sleep(2); // 발신자 본인은 수신을 따라갈 수 있는 속도로 보낸다.
                }
            } catch (Exception ignored) {
                // 서버가 연결을 끊은 경우
            } finally {
                done.set(true);
            }
        });
        flood.setDaemon(true);
        flood.start();

        long worstMillis = 0;
        int probes = 0;
        int newcomers = 0;
        while (!done.get()) {
            long start = System.nanoTime();
            other.send("ROOM_LIST");
            other.expect("ROOMS"); // 2초 안에 응답이 없으면 예외로 실패
            if (probes % 5 == 0) { // 신규 접속·닉네임 등록도 막히지 않아야 한다.
                Client newcomer = new Client(port);
                newcomer.send("HELLO", "신규" + newcomers);
                newcomer.expect("WELCOME");
                newcomer.expect("ROOMS");
                newcomer.close();
                newcomers++;
            }
            worstMillis = Math.max(worstMillis, (System.nanoTime() - start) / 1_000_000);
            probes++;
            Thread.sleep(20);
        }
        flood.join();
        check(probes > 0 && worstMillis < 1000, "느린 연결이 있어도 다른 방 응답 유지(최대 " + worstMillis + "ms, " + probes + "회)");
        check(newcomers > 0, "느린 연결이 있어도 신규 접속 처리(" + newcomers + "회)");
        for (int i = 0; i < 50 && lastSeq.get() != 19_999; i++) Thread.sleep(100);
        check(ordered.get() && lastSeq.get() == 19_999, "대량 전송 중에도 메시지 순서·전달 유지(" + (lastSeq.get() + 1) + "건)");

        // 대기열이 넘친 느린 연결은 서버가 정리한다: 느린방에는 발신자만 남는다.
        int remaining = -1;
        for (int i = 0; i < 50 && remaining != 1; i++) {
            other.send("ROOM_LIST");
            remaining = roomCount(other.expect("ROOMS"), "느린방");
            if (remaining != 1) Thread.sleep(100);
        }
        check(remaining == 1, "응답하지 않는 연결 정리");
        flooder.close();
        other.close();
        slow.close();
    }
}
