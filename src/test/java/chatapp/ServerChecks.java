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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 1주차 서버 검증: 클라이언트 3개 접속, 방 2개 메시지 분리, 퇴장·접속 종료 반영. */
public final class ServerChecks {
    private static int passed = 0;

    private static final class Client {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        String nickname;

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

        /** HELLO 후 WELCOME·ROOMS까지 받는다. */
        void hello(String nick) throws Exception {
            nickname = nick;
            send("HELLO", nick);
            expect("WELCOME");
            expect("ROOMS");
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
        whisperChecks(port);
        gameBasicChecks(port);
        gameThreePlayerChecks(port);
        gameLateJoinerChecks(port);
        gameLeaveDuringGameChecks(port);
        gameDepartedPlayerSilentChecks(port);
        gameFullEmoticonChecks(port);
        gameTimeoutChecks();

        System.out.println("\n통과 " + passed + "건");
        server.stop();
    }

    private static final List<String> EMOTICON_IDS =
            List.of("E01", "E02", "E03", "E04", "E05", "E06", "E07", "E08");

    /** 귓속말: 보낸 사람·받는 사람만 수신, 제3자·다른 방 미수신, 입력 검증. */
    private static void whisperChecks(int port) throws Exception {
        Client a = new Client(port); a.hello("귓속-가");
        a.send("ROOM_CREATE", "귓속방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");

        Client b = new Client(port); b.hello("귓속-나");
        b.send("ROOM_JOIN", room);
        b.expect("ROOM_JOINED"); b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");

        Client c = new Client(port); c.hello("귓속-다");
        c.send("ROOM_CREATE", "다른방");
        c.expect("ROOM_JOINED"); c.expect("ROOM_USERS"); c.expect("SYSTEM"); c.expect("ROOMS");
        a.expect("ROOMS"); b.expect("ROOMS");

        // 방 밖에서 귓속말 거부
        Client guest = new Client(port); guest.hello("귓속-게스트");
        guest.send("WHISPER_SEND", "귓속-가", "안 들려요");
        guest.expectError("NOT_IN_ROOM", "방 밖 귓속말 거부");

        // 본문 검증
        a.send("WHISPER_SEND", "귓속-나", "   ");
        a.expectError("INVALID_MESSAGE", "빈 귓속말 거부");

        // 대상 검증: 자기 자신, 같은 방에 없는 사람
        a.send("WHISPER_SEND", "귓속-가", "나한테?");
        a.expectError("INVALID_TARGET", "자기 자신 귓속말 거부");
        a.send("WHISPER_SEND", "귓속-다", "다른 방인데");
        a.expectError("INVALID_TARGET", "같은 방에 없는 대상 거부");

        // 성공: 보낸 사람·받는 사람만 수신
        a.send("WHISPER_SEND", "귓속-나", "안녕 귓속말");
        Message toA = a.expect("WHISPER_MESSAGE");
        check(toA.fields().equals(List.of(room, "귓속-가", "귓속-나", "안녕 귓속말")), "귓속말 발신자 수신(본문 동일)");
        Message toB = b.expect("WHISPER_MESSAGE");
        check(toB.fields().equals(toA.fields()), "귓속말 수신자 수신");
        c.expectSilence();
        check(true, "다른 방은 귓속말을 받지 않음");
        a.close(); b.close(); c.close(); guest.close();
    }

    /** gameId 전원이 같은 GAME_STARTED를 받는지 확인하고 gameId를 돌려준다. members는 시작 시점 방 전원. */
    private static String startGame(Client starter, List<Client> members) throws Exception {
        starter.send("GAME_START");
        Message reference = null;
        for (Client m : members) {
            Message started = m.expect("GAME_STARTED");
            if (reference == null) reference = started;
            else check(started.fields().equals(reference.fields()), "GAME_STARTED 참가자 전원 동일 수신");
        }
        check(reference.field(1).equals(starter.nickname), "GAME_STARTED 시작자 확인");
        return reference.field(0);
    }

    /** 마지막 선택이 아닌 GAME_CHOOSE: members(현재 방 전체 접속자) 전원의 GAME_CHOSEN만 확인한다. */
    private static void choose(Client chooser, String gameId, String choice, List<Client> members) throws Exception {
        chooser.send("GAME_CHOOSE", gameId, choice);
        for (Client m : members) {
            Message chosen = m.expect("GAME_CHOSEN");
            check(chosen.field(0).equals(gameId) && chosen.field(1).equals(chooser.nickname), "GAME_CHOSEN 브로드캐스트");
        }
    }

    /** 판정을 발생시키는 마지막 GAME_CHOOSE: GAME_CHOSEN 이후 members 전원의 동일한 GAME_RESULT까지 확인한다. */
    private static Message chooseLast(Client chooser, String gameId, String choice, List<Client> members) throws Exception {
        chooser.send("GAME_CHOOSE", gameId, choice);
        for (Client m : members) {
            Message chosen = m.expect("GAME_CHOSEN");
            check(chosen.field(0).equals(gameId) && chosen.field(1).equals(chooser.nickname), "GAME_CHOSEN 브로드캐스트");
        }
        Message reference = null;
        for (Client m : members) {
            Message result = m.expect("GAME_RESULT");
            if (reference == null) reference = result;
            else check(result.fields().equals(reference.fields()), "GAME_RESULT 전원 동일 수신");
        }
        return reference;
    }

    private static int winnerCount(Message result) {
        return Integer.parseInt(result.field(2 + 2 * Integer.parseInt(result.field(1))));
    }

    private static List<String> winners(Message result) {
        int from = 2 + 2 * Integer.parseInt(result.field(1)) + 1;
        return result.fields().subList(from, result.fields().size());
    }

    /** 시작 거부, 진행 중 재시작 거부, 선택 오류, 무승부·승리 판정과 이모티콘 지급·전송까지 2인 기준으로 검증한다. */
    private static void gameBasicChecks(int port) throws Exception {
        Client a = new Client(port); a.hello("게임-가");
        a.send("ROOM_CREATE", "게임방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");

        a.send("GAME_START");
        a.expectError("NOT_ENOUGH_PLAYERS", "혼자 있을 때 게임 시작 거부");

        Client b = new Client(port); b.hello("게임-나");
        List<Client> both = List.of(a, b);
        b.send("ROOM_JOIN", room);
        b.expect("ROOM_JOINED"); b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");

        a.send("EMOTICON_SEND", "E01");
        a.expectError("EMOTICON_NOT_OWNED", "미보유 이모티콘 송신 거부");
        a.send("EMOTICON_SEND", "X99");
        a.expectError("INVALID_EMOTICON", "없는 이모티콘 ID 거부");
        a.send("EMOTICON_SEND"); // id 필드 누락
        a.expectError("INVALID_EMOTICON", "이모티콘 ID 누락 요청 거부(연결 유지)");
        a.send("ROOM_LIST");
        check(!a.expect("ROOMS").fields().isEmpty(), "이모티콘 ID 누락 후에도 연결·요청 처리 유지");
        a.send("EMOTICON_LIST");
        check(a.expect("EMOTICON_OWNED").fields().isEmpty(), "최초 보유 목록 0개");

        String gameId = startGame(a, both);

        b.send("GAME_START");
        b.expectError("GAME_BUSY", "진행 중 재시작 거부");

        a.send("GAME_CHOOSE", "없는판", "ROCK");
        a.expectError("GAME_NOT_FOUND", "없는 gameId 선택 거부");
        a.send("GAME_CHOOSE", gameId, "총");
        a.expectError("INVALID_CHOICE", "잘못된 선택값 거부");
        a.send("GAME_CHOOSE", gameId); // choice 필드 누락
        a.expectError("INVALID_CHOICE", "선택값 누락 요청 거부(연결·참가 상태 유지)");

        choose(a, gameId, "ROCK", both);
        a.send("GAME_CHOOSE", gameId, "PAPER");
        a.expectError("ALREADY_CHOSEN", "이미 선택한 참가자 거부");
        Message draw = chooseLast(b, gameId, "ROCK", both);
        check(draw.field(1).equals("2") && winnerCount(draw) == 0, "같은 선택 전원 무승부(M=0)");
        a.expectSilence(); check(true, "무승부 보상 없음");

        gameId = startGame(a, both);
        choose(a, gameId, "ROCK", both);
        Message result = chooseLast(b, gameId, "SCISSORS", both);
        check(winnerCount(result) == 1 && winners(result).equals(List.of("게임-가")), "ROCK이 SCISSORS를 이김(승자 1명)");
        Message granted = a.expect("EMOTICON_GRANTED");
        check(granted.field(0).equals(gameId) && EMOTICON_IDS.contains(granted.field(1)), "승자에게 이모티콘 지급");
        b.expectSilence(); check(true, "패자 보상 없음");

        a.send("EMOTICON_LIST");
        check(a.expect("EMOTICON_OWNED").fields().equals(List.of(granted.field(1))), "보유 목록에 반영");

        a.send("EMOTICON_SEND", granted.field(1));
        Message sentA = a.expect("EMOTICON_MESSAGE");
        check(sentA.fields().equals(List.of(room, "게임-가", granted.field(1))), "보유 이모티콘 전송(보낸 사람 포함)");
        check(b.expect("EMOTICON_MESSAGE").fields().equals(sentA.fields()), "같은 방 전체 수신");

        a.close(); b.close();
    }

    /** 3명 방에서 두 종류 중 여러 명 승자·1명만 승자·세 종류 무승부를 검증한다. */
    private static void gameThreePlayerChecks(int port) throws Exception {
        Client a = new Client(port); a.hello("셋-가");
        a.send("ROOM_CREATE", "셋방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client b = new Client(port); b.hello("셋-나");
        b.send("ROOM_JOIN", room);
        b.expect("ROOM_JOINED"); b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client c = new Client(port); c.hello("셋-다");
        c.send("ROOM_JOIN", room);
        c.expect("ROOM_JOINED"); c.expect("ROOM_USERS"); c.expect("SYSTEM"); c.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        List<Client> all = List.of(a, b, c);

        String gameId = startGame(a, all);
        choose(a, gameId, "ROCK", all);
        choose(b, gameId, "ROCK", all);
        Message multi = chooseLast(c, gameId, "SCISSORS", all);
        check(winnerCount(multi) == 2 && Set.copyOf(winners(multi)).equals(Set.of("셋-가", "셋-나")),
                "3명 중 두 종류에서 승자 여러 명");
        a.expect("EMOTICON_GRANTED"); b.expect("EMOTICON_GRANTED"); c.expectSilence();
        check(true, "다승자 각각 보상, 패자 없음");

        gameId = startGame(a, all);
        choose(a, gameId, "ROCK", all);
        choose(b, gameId, "SCISSORS", all);
        Message single = chooseLast(c, gameId, "SCISSORS", all);
        check(winnerCount(single) == 1 && winners(single).equals(List.of("셋-가")), "3명 중 승자 1명");
        a.expect("EMOTICON_GRANTED"); b.expectSilence(); c.expectSilence();
        check(true, "단독 승자만 보상");

        gameId = startGame(a, all);
        choose(a, gameId, "ROCK", all);
        choose(b, gameId, "PAPER", all);
        Message threeWay = chooseLast(c, gameId, "SCISSORS", all);
        check(winnerCount(threeWay) == 0, "세 종류 전원 다름 → 무승부");
        a.expectSilence(); b.expectSilence(); c.expectSilence();
        check(true, "세 종류 무승부 보상 없음");

        a.close(); b.close(); c.close();
    }

    /** 중도 입장자는 GAME_STARTED 없이 결과만 받고, GAME_CHOOSE는 거부된다. */
    private static void gameLateJoinerChecks(int port) throws Exception {
        Client a = new Client(port); a.hello("중도-가");
        a.send("ROOM_CREATE", "중도방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client b = new Client(port); b.hello("중도-나");
        b.send("ROOM_JOIN", room);
        b.expect("ROOM_JOINED"); b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");

        String gameId = startGame(a, List.of(a, b));

        Client d = new Client(port); d.hello("중도-다");
        d.send("ROOM_JOIN", room);
        d.expect("ROOM_JOINED"); d.expect("ROOM_USERS"); d.expect("SYSTEM"); d.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");

        d.send("GAME_CHOOSE", gameId, "ROCK");
        d.expectError("GAME_NOT_FOUND", "중도 입장자는 참가자가 아님");

        List<Client> roomAll = List.of(a, b, d);
        choose(a, gameId, "ROCK", roomAll);
        Message result = chooseLast(b, gameId, "SCISSORS", roomAll); // d도 동일 GAME_RESULT 수신(위에서 확인)
        check(winners(result).equals(List.of("중도-가")), "중도 입장자가 있어도 판정은 정상");
        a.expect("EMOTICON_GRANTED");
        b.expectSilence(); d.expectSilence();

        a.close(); b.close(); d.close();
    }

    /** 참가자 퇴장 시 남은 인원이 2명 이상이면 즉시 판정, 2명 미만이면 PLAYERS_LEFT로 취소한다. */
    private static void gameLeaveDuringGameChecks(int port) throws Exception {
        Client a = new Client(port); a.hello("퇴장-가");
        a.send("ROOM_CREATE", "퇴장방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client b = new Client(port); b.hello("퇴장-나");
        b.send("ROOM_JOIN", room);
        b.expect("ROOM_JOINED"); b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client c = new Client(port); c.hello("퇴장-다");
        c.send("ROOM_JOIN", room);
        c.expect("ROOM_JOINED"); c.expect("ROOM_USERS"); c.expect("SYSTEM"); c.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");

        // a, c가 먼저 선택, b는 선택 전 퇴장 → 남은 참가자(a, c)가 모두 선택한 상태이므로 즉시 판정
        String gameId = startGame(a, List.of(a, b, c));
        choose(a, gameId, "ROCK", List.of(a, b, c));
        choose(c, gameId, "SCISSORS", List.of(a, b, c));
        b.send("ROOM_LEAVE");
        b.expect("ROOM_LEFT");
        Message result = null;
        for (Client m : List.of(a, c)) {
            Message r = m.expect("GAME_RESULT");
            if (result == null) result = r; else check(r.fields().equals(result.fields()), "퇴장 후 판정 동일 수신");
        }
        check(winners(result).equals(List.of("퇴장-가")), "참가자 퇴장 후 남은 인원(선택 완료)으로 즉시 판정");
        a.expect("EMOTICON_GRANTED");
        // c는 보상이 없으므로 다음 메시지가 바로 SYSTEM이어야 한다(아니면 아래 expect가 실패한다).
        for (Client m : List.of(a, c)) { m.expect("SYSTEM"); m.expect("ROOM_USERS"); }
        check(true, "패자에게 보상 없음");
        a.expect("ROOMS"); c.expect("ROOMS"); b.expect("ROOMS");

        // 남은 a, c로 새 게임 시작 후 c가 선택 전 퇴장 → 참가자 1명으로 PLAYERS_LEFT 취소
        String gameId2 = startGame(a, List.of(a, c));
        c.send("ROOM_LEAVE");
        c.expect("ROOM_LEFT");
        Message canceled = a.expect("GAME_CANCELED");
        check(canceled.field(0).equals(gameId2) && canceled.field(1).equals("PLAYERS_LEFT"), "참가자 2명 미만 → PLAYERS_LEFT");
        a.expect("SYSTEM"); a.expect("ROOM_USERS");
        a.expect("ROOMS"); c.expect("ROOMS");
        a.expectSilence(); check(true, "취소된 게임은 보상 없음");

        a.close(); b.close(); c.close();
    }

    /** 참가자가 충분히 남아 게임이 계속되는 동안, 먼저 퇴장한 사람에게는 이후 게임 이벤트가 가지 않는다. */
    private static void gameDepartedPlayerSilentChecks(int port) throws Exception {
        Client a = new Client(port); a.hello("이탈-가");
        a.send("ROOM_CREATE", "이탈방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client e = new Client(port); e.hello("이탈-나");
        e.send("ROOM_JOIN", room);
        e.expect("ROOM_JOINED"); e.expect("ROOM_USERS"); e.expect("SYSTEM"); e.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client f = new Client(port); f.hello("이탈-다");
        f.send("ROOM_JOIN", room);
        f.expect("ROOM_JOINED"); f.expect("ROOM_USERS"); f.expect("SYSTEM"); f.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        e.expect("ROOM_USERS"); e.expect("SYSTEM"); e.expect("ROOMS");

        String gameId = startGame(a, List.of(a, e, f));

        // 아무도 선택하기 전에 e가 퇴장: 남은 참가자(a, f)는 2명이라 게임이 계속된다.
        e.send("ROOM_LEAVE");
        e.expect("ROOM_LEFT");
        for (Client m : List.of(a, f)) { m.expect("SYSTEM"); m.expect("ROOM_USERS"); }
        a.expect("ROOMS"); f.expect("ROOMS"); e.expect("ROOMS");

        choose(a, gameId, "ROCK", List.of(a, f));
        Message result = chooseLast(f, gameId, "SCISSORS", List.of(a, f));
        check(winners(result).equals(List.of("이탈-가")), "남은 두 참가자로 게임이 정상 종료");
        a.expect("EMOTICON_GRANTED"); f.expectSilence();
        e.expectSilence(); check(true, "방을 나간 사람에게는 이후 게임 이벤트가 가지 않음");

        a.close(); e.close(); f.close();
    }

    /** 승자당 한 번, 미보유 중 무작위 1개, 8개 모두 보유하면 더 이상 지급하지 않는다. */
    private static void gameFullEmoticonChecks(int port) throws Exception {
        Client winner = new Client(port); winner.hello("모음-가");
        winner.send("ROOM_CREATE", "모음방");
        String room = winner.expect("ROOM_JOINED").field(0);
        winner.expect("ROOM_USERS"); winner.expect("SYSTEM"); winner.expect("ROOMS");
        Client loser = new Client(port); loser.hello("모음-나");
        loser.send("ROOM_JOIN", room);
        loser.expect("ROOM_JOINED"); loser.expect("ROOM_USERS"); loser.expect("SYSTEM"); loser.expect("ROOMS");
        winner.expect("ROOM_USERS"); winner.expect("SYSTEM"); winner.expect("ROOMS");
        List<Client> both = List.of(winner, loser);

        Set<String> collected = new java.util.LinkedHashSet<>();
        for (int i = 0; i < EMOTICON_IDS.size(); i++) {
            String gameId = startGame(winner, both);
            choose(winner, gameId, "ROCK", both);
            chooseLast(loser, gameId, "SCISSORS", both);
            Message granted = winner.expect("EMOTICON_GRANTED");
            check(collected.add(granted.field(1)), "매번 아직 없는 이모티콘을 지급(" + (i + 1) + "회차)");
            loser.expectSilence();
        }
        check(collected.equals(Set.copyOf(EMOTICON_IDS)), "8종 전부 보유");
        winner.send("EMOTICON_LIST");
        List<String> sorted = new ArrayList<>(EMOTICON_IDS);
        Collections.sort(sorted);
        check(winner.expect("EMOTICON_OWNED").fields().equals(sorted), "보유 목록 8개 오름차순");

        String gameId = startGame(winner, both);
        choose(winner, gameId, "ROCK", both);
        chooseLast(loser, gameId, "SCISSORS", both);
        winner.expectSilence(); check(true, "8개 모두 보유하면 승리해도 추가 지급 없음");
        loser.expectSilence();

        winner.close(); loser.close();
    }

    /** 선택 제한 시간(짧게 설정)이 지나면 미선택자를 제외하고 판정하거나, 2명 미만이면 TIMEOUT으로 취소한다. */
    private static void gameTimeoutChecks() throws Exception {
        ChatServer server = new ChatServer(0, 150, new Random());
        Thread thread = new Thread(server::serve); thread.setDaemon(true); thread.start();
        int port = server.port();

        Client a = new Client(port); a.hello("제한-가");
        a.send("ROOM_CREATE", "제한방");
        String room = a.expect("ROOM_JOINED").field(0);
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        Client b = new Client(port); b.hello("제한-나");
        b.send("ROOM_JOIN", room);
        b.expect("ROOM_JOINED"); b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");

        // 1명만 선택하고 시간 초과 → TIMEOUT 취소, 보상 없음
        String gameId = startGame(a, List.of(a, b));
        choose(a, gameId, "ROCK", List.of(a, b));
        Message canceled = null;
        for (Client m : List.of(a, b)) {
            Message c = m.expect("GAME_CANCELED");
            if (canceled == null) canceled = c; else check(c.fields().equals(canceled.fields()), "취소 동일 수신");
        }
        check(canceled.field(0).equals(gameId) && canceled.field(1).equals("TIMEOUT"), "1명만 선택 후 시간 초과 → TIMEOUT");
        a.expectSilence(); check(true, "시간 초과 취소에는 보상이 없음");

        // 3명 중 2명 선택 후 시간 초과 → 선택한 둘로 판정
        Client c = new Client(port); c.hello("제한-다");
        c.send("ROOM_JOIN", room);
        c.expect("ROOM_JOINED"); c.expect("ROOM_USERS"); c.expect("SYSTEM"); c.expect("ROOMS");
        a.expect("ROOM_USERS"); a.expect("SYSTEM"); a.expect("ROOMS");
        b.expect("ROOM_USERS"); b.expect("SYSTEM"); b.expect("ROOMS");
        List<Client> all = List.of(a, b, c);

        gameId = startGame(a, all);
        choose(a, gameId, "ROCK", all);
        choose(b, gameId, "SCISSORS", all);
        Message result = null;
        for (Client m : all) {
            Message r = m.expect("GAME_RESULT");
            if (result == null) result = r; else check(r.fields().equals(result.fields()), "시간 초과 판정 동일 수신");
        }
        check(result.field(1).equals("2") && winners(result).equals(List.of("제한-가")),
                "2명만 선택하고 시간 초과해도 그 둘로 판정");
        a.expect("EMOTICON_GRANTED"); b.expectSilence(); c.expectSilence();

        a.close(); b.close(); c.close();
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
