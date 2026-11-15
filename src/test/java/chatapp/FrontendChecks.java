package chatapp;

import chatapp.client.ui.*;
import chatapp.client.network.*;
import chatapp.common.Message;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 테스트 전용 TCP 상대를 사용한다. 실제 백엔드 기능을 구현하는 서버가 아니다. */
public final class FrontendChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        wireChecks();
        demoUiChecks();
        bubbleRenderingChecks();
        scrollChecks();
        draftChecks();
        socketUiChecks();
        rejectedLoginCheck();
        whisperUiChecks();
        gameUiChecks();
        animatedDemoChecks();
        stickerRenderingChecks();
        System.out.println("PASS: " + checks + " checks. Screenshots: build/screenshots/");
    }
    private static List<String> highlightedRooms(ChatPanel panel) throws Exception {
        return edt(() -> highlightedRows((JList<?>) find(panel, "roomList")));
    }
    private static <T> List<String> highlightedRows(JList<T> list) {
        List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            T room = list.getModel().getElementAt(i);
            Component cell = list.getCellRenderer().getListCellRendererComponent(list, room, i, list.isSelectedIndex(i), false);
            cell.setSize(280, 88);
            BufferedImage image = new BufferedImage(280, 88, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            cell.paint(graphics); graphics.dispose();
            if ((image.getRGB(140, 5) & 0xffffff) == 0xFFF6CE) result.add(room.toString());
        }
        return result;
    }
    private static void wireChecks() throws Exception {
        Message original = Message.of("CHAT_MESSAGE", "방-1", "김지원", "한글\t탭\n줄바꿈", "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        original.write(output); Message.of("ROOM_LEFT").write(output);
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1, len)); }
        };
        DataInputStream input = new DataInputStream(fragmented);
        check(original.equals(Message.read(input)), "한글·구분문자·빈 필드·분할 수신 보존");
        check(Message.read(input).type().equals("ROOM_LEFT"), "연속 프레임 분리");
        for (int length : new int[]{-1, 0, Message.MAX_BYTES + 1}) {
            bytes.reset(); output.writeInt(length);
            try { Message.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))); throw new AssertionError("길이 검사 누락"); }
            catch (IOException expected) { checks++; }
        }
    }
    private static void demoUiChecks() throws Exception {
        ChatPanel panel = edt(() -> { Theme.install(); return new ChatPanel(); });
        try {
            snapshot(panel, "01-connection.png");
            click(panel, "demo");
            check(status(panel).contains("닉네임"), "빈 닉네임 거부");
            set(panel, "nickname", "김지원"); click(panel, "demo");
            await(() -> listSize(panel, "roomList") == 2, "데모 방 목록");
            set(panel, "roomSearch", "먹지");
            check(listSize(panel,"roomList")==1, "대화 이름 검색");
            set(panel, "roomSearch", "없는이름");
            check(listSize(panel,"roomList")==0, "검색 결과 없을 때 입장 방지");
            set(panel, "roomSearch", "");
            check(listSize(panel,"roomList")==2, "검색 해제 후 전체 목록 복원");
            snapshot(panel, "02-lobby.png");
            click(panel,"newConversation");
            check(ui(() -> field(panel,"roomName").isVisible() && field(panel,"roomName").getParent().isVisible()), "새 대화 입력 영역 열기");
            set(panel, "roomName", "1주차 프론트엔드 회의"); click(panel, "create");
            await(() -> label(panel, "roomTitle").equals("1주차 프론트엔드 회의"), "방 생성 후 입장");
            await(() -> listSize(panel, "users") == 1, "방 참가자 표시");
            set(panel, "messageInput", " "); click(panel, "send");
            check(status(panel).contains("1~1000"), "빈 메시지 거부");
            set(panel, "messageInput", "접속·로비·채팅 화면 연결을 확인했어요."); click(panel, "send");
            await(() -> textArea(panel).contains("접속·로비·채팅 화면"), "데모 메시지 표시");
            check(edt(() -> field(panel, "messageInput").getText()).isEmpty(), "서버 응답 후 입력 초기화");
            set(panel, "messageInput", "다음 단계는 정윤님과 프로토콜 검토입니다."); click(panel, "send");
            await(() -> textArea(panel).contains("프로토콜 검토"), "연속 메시지 전송");
            snapshot(panel, "03-chat.png");
            check(ui(() -> active(find(panel,"roomList"))), "채팅 중 왼쪽 목록 유지");
            check(!ui(() -> find(panel,"membersPanel").isVisible()), "멤버 패널 기본 접힘");
            click(panel,"membersToggle");
            check(ui(() -> find(panel,"membersPanel").isVisible()), "멤버 패널 펼치기");
            snapshot(panel,"06-members-expanded.png");
            edt(() -> { panel.setSize(960,680); layout(panel); return null; });
            check(ui(() -> ((JList<?>)find(panel,"roomList")).getWidth() <= find(panel,"roomList").getParent().getWidth()), "좁은 창에서 대화 목록 가로 넘침 없음");
            click(panel,"membersToggle");
            check(!ui(() -> find(panel,"membersPanel").isVisible()), "멤버 패널 다시 접기");
            check(!ui(() -> find(panel,"status").isVisible()), "정상 전송 안내 숨김");
            selectRoom(panel,"오늘 뭐 먹지?");
            check(highlightedRooms(panel).equals(List.of("1주차 프론트엔드 회의")), "다른 항목 선택 중에도 열린 방만 강조");
            openSelected(panel);
            await(() -> label(panel,"roomTitle").equals("오늘 뭐 먹지?"), "채팅 중 다른 방 전환");
            check(highlightedRooms(panel).equals(List.of("오늘 뭐 먹지?")), "방 전환 후 새 방만 강조");
            snapshot(panel,"08-room-selection.png");
            check(!textArea(panel).contains("프로토콜 검토"), "전환한 방에 이전 대화 미표시");
            click(panel, "leave");
            await(() -> status(panel).contains("로비로"), "방 퇴장");
            check(highlightedRooms(panel).isEmpty(), "퇴장 후 열린 방 강조 해제");
            check(!ui(() -> field(panel,"roomName").getParent().isVisible()), "방 생성 후 입력 영역 접기");
            snapshot(panel,"05-lobby-recent.png");
            selectRoom(panel,"우리들의 아지트");
            edt(() -> { ((JList<?>)find(panel,"roomList")).getActionMap().get("openRoom").actionPerformed(new java.awt.event.ActionEvent(panel,0,"openRoom")); return null; });
            await(() -> label(panel, "roomTitle").equals("우리들의 아지트"), "기존 방 입장");
            check(!textArea(panel).contains("접속·로비·채팅 화면"), "이전 방 대화 격리");
            click(panel, "disconnect");
            await(() -> enabled(panel, "demo"), "명시적 연결 종료");
            click(panel, "demo"); await(() -> listSize(panel, "roomList") == 2, "재접속 시 데모 초기화");
        } finally { edt(() -> { panel.close(); return null; }); }
    }
    private static void socketUiChecks() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            CountDownLatch finish = new CountDownLatch(1);
            CountDownLatch sent = new CountDownLatch(1), acknowledge = new CountDownLatch(1);
            FutureTask<Void> fixture = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    Message hello = Message.read(input);
                    if (!hello.equals(Message.of("HELLO", "김지원"))) throw new AssertionError("HELLO 요청 오류");
                    Message.of("WELCOME", "김지원").write(output);
                    Message.of("ROOMS", "r1", "TCP 테스트방", "1", "r2", "다음 대화", "0").write(output);
                    Message join = Message.read(input);
                    if (!join.equals(Message.of("ROOM_JOIN", "r1"))) throw new AssertionError("ROOM_JOIN 요청 오류");
                    Message.of("ROOM_JOINED", "r1", "TCP 테스트방").write(output);
                    Message.of("ROOM_USERS", "r1", "김지원", "테스트상대").write(output);
                    Message chat = Message.read(input);
                    if (!chat.equals(Message.of("CHAT_SEND", "실제 소켓 테스트"))) throw new AssertionError("CHAT_SEND 요청 오류");
                    sent.countDown();
                    if (!acknowledge.await(5,TimeUnit.SECONDS)) throw new AssertionError("전송 확인 대기 시간 초과");
                    Message.of("CHAT_MESSAGE", "other-room", "테스트상대", "다른 방 내용").write(output);
                    Message.of("CHAT_MESSAGE", "r1", "김지원", chat.field(0)).write(output);
                    Message.of("ERROR", "TEST_ERROR", "테스트 오류 안내").write(output);
                    if (!Message.read(input).equals(Message.of("ROOM_LEAVE"))) throw new AssertionError("전환 시 퇴장 먼저 요청");
                    Message.of("ERROR", "LEAVE_FAILED", "테스트 퇴장 거부").write(output);
                    if (!Message.read(input).equals(Message.of("ROOM_LEAVE"))) throw new AssertionError("퇴장 실패 후 입장 요청 금지");
                    Message.of("ROOM_LEFT").write(output);
                    if (!Message.read(input).equals(Message.of("ROOM_JOIN","r2"))) throw new AssertionError("퇴장 확인 후 다음 방 입장");
                    Message.of("ROOM_JOINED","r2","다음 대화").write(output);
                    Message.of("ROOM_USERS","r2","김지원").write(output);
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("UI 확인 시간 초과");
                }
                return null;
            });
            Thread thread = new Thread(fixture, "test-only-tcp-fixture"); thread.setDaemon(true); thread.start();
            ChatPanel panel = edt(ChatPanel::new);
            try {
                set(panel, "nickname", "김지원"); set(panel, "host", server.getInetAddress().getHostAddress());
                set(panel, "port", String.valueOf(server.getLocalPort())); click(panel, "connect");
                await(() -> listSize(panel, "roomList") == 2, "TCP 로그인·목록 수신");
                edt(() -> { ((JList<?>) find(panel, "roomList")).setSelectedIndex(0); return null; });
                openSelected(panel); await(() -> listSize(panel, "users") == 2, "TCP 입장·참가자 수신");
                set(panel, "messageInput", "실제 소켓 테스트"); click(panel, "send");
                check(sent.await(5,TimeUnit.SECONDS),"서버 전송 확인 지연 준비");
                set(panel,"messageInput","전송 중 새로 쓴 초안"); acknowledge.countDown();
                await(() -> textArea(panel).contains("실제 소켓 테스트"), "TCP 요청·메시지 수신");
                check(ui(() -> field(panel,"messageInput").getText()).equals("전송 중 새로 쓴 초안"),"늦은 전송 응답이 새 초안을 지우지 않음");
                check(!textArea(panel).contains("다른 방 내용"), "다른 방 이벤트 표시 차단");
                await(() -> status(panel).contains("테스트 오류 안내"), "서버 오류 화면 표시");
                check(ui(() -> find(panel,"status").isVisible()), "서버 오류는 숨기지 않음");
                edt(() -> { ((JList<?>)find(panel,"roomList")).setSelectedIndex(1); return null; });
                openSelected(panel);
                await(() -> status(panel).contains("테스트 퇴장 거부"), "방 전환 실패 안내");
                check(label(panel,"roomTitle").equals("TCP 테스트방"), "퇴장 거부 시 기존 방 유지");
                check(highlightedRooms(panel).equals(List.of("TCP 테스트방")), "전환 거부 시 기존 방만 강조");
                openSelected(panel);
                await(() -> label(panel,"roomTitle").equals("다음 대화"), "TCP 퇴장 응답 이후 입장 순서 검증");
                finish.countDown();
                await(() -> status(panel).contains("연결이 종료"), "서버 종료 감지·접속 화면 복귀");
                check(enabled(panel, "connect"), "종료 후 재접속 가능");
                fixture.get(5, TimeUnit.SECONDS);
            } finally { acknowledge.countDown(); finish.countDown(); edt(() -> { panel.close(); return null; }); }
        }
    }
    private static void bubbleRenderingChecks() throws Exception {
        edt(() -> {
            ConversationView view = new ConversationView();
            JScrollPane scroll = new JScrollPane(view); scroll.setSize(440, 300); layout(scroll);
            int before = view.getPreferredSize().height;
            String longMessage = "긴 메시지도 말풍선 안에서 자연스럽게 줄바꿈되어야 합니다. ".repeat(15);
            view.addMessage("상대", longMessage, false);
            check(view.getPreferredSize().height > before + 150, "긴 말풍선 줄바꿈·높이 확장");
            check(view.getScrollableTracksViewportWidth(), "말풍선 너비가 화면에 맞춰짐");
            for (int i=0; i<301; i++) view.addMessage("사용자", "번호-"+i, true);
            check(!view.getText().contains(longMessage) && view.getText().contains("번호-300"), "대화 300개 보관 상한");
            view.clear(); check(view.getText().isEmpty(), "말풍선 초기화");
            return null;
        });
        ChatPanel panel = edt(ChatPanel::new);
        try {
            set(panel,"nickname","김지원"); click(panel,"demo");
            await(() -> listSize(panel,"roomList")==2,"미리보기 데모 준비");
            set(panel,"roomName","퇴근 후 수다"); click(panel,"create");
            await(() -> listSize(panel,"users")==1,"미리보기 입장");
            edt(() -> {
                ((JLabel)find(panel,"notice")).setText("디자인 미리보기 · 아래 대화는 예시입니다");
                ConversationView view = (ConversationView)find(panel,"transcript"); view.clear();
                view.addNotice("퇴근 후 수다에 오신 걸 환영해요");
                view.addMessage("정윤 · 예시", "지원아 안녕! 오늘 여기서 이야기할까?", false);
                view.addMessage("정윤 · 예시", "편한 시간에 답장해 줘 :)", false);
                view.addMessage("김지원", "좋아! 훨씬 편하게 이야기할 수 있겠다 :)", true);
                view.addMessage("정윤 · 예시", "노란 말풍선 귀엽다. 잠깐 쉬었다가 같이 시작하자!", false);
                view.addMessage("김지원", "응! 커피 한 잔 가져올게", true);
                view.addMessage("김지원", "금방 올게!", true);
                return null;
            });
            snapshot(panel,"04-design-preview.png");
            edt(() -> {
                ConversationView view=(ConversationView)find(panel,"transcript");
                for(int i=0;i<25;i++) view.addMessage("예시 친구 "+i,"스크롤 확인용 예시 대화 "+i,false);
                return null;
            });
            edt(() -> null);
            edt(() -> {
                JScrollPane scroll=(JScrollPane)find(panel,"messageScroll");
                scroll.getViewport().setViewPosition(new Point(0,60));
                ((ConversationView)find(panel,"transcript")).addMessage("정윤 · 예시","새 메시지가 도착했어요",false);
                return null;
            });
            await(() -> ui(() -> find(panel,"newMessages").isVisible()),"화면의 새 메시지 버튼 표시");
            snapshot(panel,"07-new-message-button.png");
            click(panel,"newMessages");
            await(() -> !ui(() -> find(panel,"newMessages").isVisible()),"새 메시지 버튼 클릭 후 해제");
        } finally { edt(() -> { panel.close(); return null; }); }
    }
    private static void rejectedLoginCheck() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            FutureTask<Void> fixture = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    Message.read(new DataInputStream(socket.getInputStream()));
                    Message.of("ERROR", "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다.").write(new DataOutputStream(socket.getOutputStream()));
                }
                return null;
            });
            Thread thread = new Thread(fixture); thread.setDaemon(true); thread.start();
            try (SocketTransport transport = new SocketTransport()) {
                try {
                    transport.connect(server.getInetAddress().getHostAddress(), server.getLocalPort(), "중복", new ChatTransport.Listener() {
                        public void onMessage(Message message) { throw new AssertionError("로그인 거부 후 연결 성공 처리"); }
                        public void onDisconnected(String reason) { }
                    });
                    throw new AssertionError("중복 닉네임 오류 누락");
                } catch (IOException expected) { check(expected.getMessage().contains("이미 사용"), "로그인 거부 이유 보존"); }
            }
            fixture.get(5, TimeUnit.SECONDS);
        }
    }
    private static void scrollChecks() throws Exception {
        ConversationView view = edt(ConversationView::new);
        JScrollPane pane = edt(() -> {
            JScrollPane next = new JScrollPane(view); next.setSize(480,240); layout(next);
            view.attachScrollPane(next, unseen -> { });
            for(int i=0;i<30;i++) view.addMessage("친구"+i,"이전 대화 "+i,false);
            return next;
        });
        edt(() -> null);
        check(ui(() -> pane.getViewport().getViewPosition().y>100),"최신 대화를 보고 있을 때 아래로 따라감");
        edt(() -> { pane.getViewport().setViewPosition(new Point(0,80)); view.addMessage("친구","읽는 중 도착한 메시지",false); return null; });
        edt(() -> null);
        check(ui(() -> pane.getViewport().getViewPosition().y)==80,"이전 대화를 읽는 위치 유지");
        check(ui(view::hasUnseenMessages),"새 메시지 알림 상태 표시");
        edt(() -> { view.jumpToLatest(); return null; }); edt(() -> null);
        check(!ui(view::hasUnseenMessages),"최신으로 이동하면 알림 해제");
        check(ui(() -> pane.getViewport().getViewPosition().y+pane.getViewport().getHeight()>=view.getPreferredSize().height-1),"최신 메시지 위치로 이동");
        edt(() -> { view.addMessage("친구","이어지는 새 메시지",false); return null; }); edt(() -> null);
        check(!ui(view::hasUnseenMessages),"하단에서 후속 메시지 자동 추적");
        edt(() -> { view.addMessage("이전 방","예약된 스크롤",false); view.clear(); return null; }); edt(() -> null);
        check(ui(view::getText).isEmpty() && !ui(view::hasUnseenMessages),"방 변경 시 이전 스크롤 예약 무효화");
        edt(() -> {
            ConversationView grouped=new ConversationView(), separate=new ConversationView();
            grouped.addMessage("친구","첫 메시지",false); grouped.addMessage("친구","두 번째 메시지",false);
            separate.addMessage("친구","첫 메시지",false); separate.addMessage("다른 친구","두 번째 메시지",false);
            check(grouped.getPreferredSize().height<separate.getPreferredSize().height,"연속 발신자 묶음의 간격·이름 중복 축소");
            return null;
        });
    }
    private static void draftChecks() throws Exception {
        ChatPanel panel=edt(ChatPanel::new);
        try {
            set(panel,"nickname","김지원");click(panel,"demo");await(() -> listSize(panel,"roomList")==2,"초안 검사 접속");
            selectRoom(panel,"우리들의 아지트");openSelected(panel);await(() -> label(panel,"roomTitle").equals("우리들의 아지트"),"A방 입장");
            set(panel,"messageInput","A방에 남긴 초안");
            selectRoom(panel,"오늘 뭐 먹지?");openSelected(panel);await(() -> label(panel,"roomTitle").equals("오늘 뭐 먹지?"),"B방 입장");
            check(ui(() -> field(panel,"messageInput").getText()).isEmpty(),"다른 방에 초안이 섞이지 않음");
            set(panel,"messageInput","B방에 남긴 초안");
            selectRoom(panel,"우리들의 아지트");openSelected(panel);await(() -> label(panel,"roomTitle").equals("우리들의 아지트"),"A방 재입장");
            check(ui(() -> field(panel,"messageInput").getText()).equals("A방에 남긴 초안"),"A방 초안 복원");
            selectRoom(panel,"오늘 뭐 먹지?");openSelected(panel);await(() -> label(panel,"roomTitle").equals("오늘 뭐 먹지?"),"B방 재입장");
            check(ui(() -> field(panel,"messageInput").getText()).equals("B방에 남긴 초안"),"B방 초안 복원");
            click(panel,"send");await(() -> textArea(panel).contains("B방에 남긴 초안"),"초안 전송");
            check(ui(() -> ((JList<?>)find(panel,"roomList")).getModel().getElementAt(0).toString()).equals("오늘 뭐 먹지?"),"메시지를 받은 방을 목록 맨 위로 정렬");
            check(ui(() -> ((JList<?>)find(panel,"roomList")).getSelectedValue().toString()).equals("오늘 뭐 먹지?"),"정렬 후 선택한 방 유지");
            selectRoom(panel,"우리들의 아지트");openSelected(panel);await(() -> label(panel,"roomTitle").equals("우리들의 아지트"),"정렬 후 A방 이동");
            selectRoom(panel,"오늘 뭐 먹지?");openSelected(panel);await(() -> label(panel,"roomTitle").equals("오늘 뭐 먹지?"),"전송 후 B방 재입장");
            check(ui(() -> field(panel,"messageInput").getText()).isEmpty(),"전송한 초안은 다시 나타나지 않음");
            set(panel,"messageInput","재시작 전 초안");click(panel,"disconnect");click(panel,"demo");
            await(() -> listSize(panel,"roomList")==2,"세션 재연결");selectRoom(panel,"오늘 뭐 먹지?");openSelected(panel);
            await(() -> label(panel,"roomTitle").equals("오늘 뭐 먹지?"),"새 세션 방 입장");
            check(ui(() -> field(panel,"messageInput").getText()).isEmpty(),"다른 세션에 초안 전달하지 않음");
        } finally { edt(() -> { panel.close(); return null; }); }
    }
    private static void whisperUiChecks() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            BlockingQueue<Message> requests = new LinkedBlockingQueue<>();
            CompletableFuture<DataOutputStream> response = new CompletableFuture<>();
            FutureTask<Void> fixture = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    Message.read(in);
                    Message.of("WELCOME", "김지원").write(out);
                    Message.of("ROOMS", "r1", "귓속말 테스트", "2").write(out);
                    if (!Message.read(in).equals(Message.of("ROOM_JOIN", "r1"))) throw new AssertionError("입장 요청");
                    Message.of("ROOM_JOINED", "r1", "귓속말 테스트").write(out);
                    Message.of("ROOM_USERS", "r1", "김지원", "정윤", "민수").write(out);
                    response.complete(out);
                    try { while (true) requests.add(Message.read(in)); } catch (EOFException expected) { }
                }
                return null;
            });
            Thread worker = new Thread(fixture, "whisper-tcp-fixture"); worker.setDaemon(true); worker.start();
            ChatPanel panel = edt(ChatPanel::new);
            try {
                set(panel, "nickname", "김지원"); set(panel, "host", server.getInetAddress().getHostAddress());
                set(panel, "port", String.valueOf(server.getLocalPort())); click(panel, "connect");
                await(() -> listSize(panel, "roomList") == 1, "귓속말 TCP 접속");
                selectRoom(panel, "귓속말 테스트"); openSelected(panel);
                await(() -> listSize(panel, "users") == 3, "귓속말 참가자 목록");
                DataOutputStream out = response.get(5, TimeUnit.SECONDS);
                click(panel, "whisper");
                check(ui(() -> ((JComboBox<?>)find(panel,"whisperTarget")).getItemCount()) == 2, "자신을 귓속말 대상에서 제외");
                set(panel,"whisperInput","비밀 이야기"); click(panel,"sendWhisper");
                check(status(panel).contains("상대"), "대상 미선택 시 전송 차단");
                edt(() -> { ((JComboBox<?>) find(panel,"whisperTarget")).setSelectedItem("정윤"); return null; });
                set(panel,"messageInput","전체 대화 초안"); click(panel,"sendWhisper");
                check(Message.of("WHISPER_SEND","정윤","비밀 이야기").equals(requests.poll(5,TimeUnit.SECONDS)), "노션 규칙대로 귓속말 전송");
                check(!textArea(panel).contains("비밀 이야기"), "서버 응답 전에 전송 성공으로 표시하지 않음");
                Message.of("WHISPER_MESSAGE","other","정윤","김지원","다른 방 비밀").write(out);
                Message.of("WHISPER_MESSAGE","r1","정윤","민수","제삼자 비밀").write(out);
                Message.of("WHISPER_MESSAGE","r1","정윤","김지원","수신 이야기").write(out);
                await(() -> textArea(panel).contains("수신 이야기"), "귓속말 수신 표시");
                check(!textArea(panel).contains("다른 방 비밀") && !textArea(panel).contains("제삼자 비밀"), "다른 방과 제삼자 귓속말 화면 차단");
                check(!enabled(panel,"sendWhisper"), "수신 귓속말이 내 전송 대기를 끝내지 않음");
                set(panel,"whisperInput","응답 대기 중 새 초안");
                Message.of("WHISPER_MESSAGE","r1","김지원","정윤","비밀 이야기").write(out);
                await(() -> enabled(panel,"sendWhisper"), "자신의 전송 응답으로 대기 종료");
                check(ui(() -> field(panel,"whisperInput").getText()).equals("응답 대기 중 새 초안"), "늦은 응답에도 새 귓속말 초안 보존");
                check(ui(() -> field(panel,"messageInput").getText()).equals("전체 대화 초안"), "일반 채팅 초안과 분리");
                click(panel,"sendWhisper"); requests.poll(5,TimeUnit.SECONDS);
                Message.of("ERROR","INVALID_TARGET","상대가 방을 나갔습니다.").write(out);
                await(() -> status(panel).contains("상대가 방을"), "귓속말 오류 안내");
                check(!ui(() -> field(panel,"whisperInput").getText()).isEmpty(), "실패한 귓속말 초안 보존");
                Message.of("ROOM_USERS","r1","김지원","민수").write(out);
                await(() -> listSize(panel,"users") == 2, "상대 퇴장 반영");
                check(ui(() -> ((JComboBox<?>)find(panel,"whisperTarget")).getSelectedItem()) == null, "상대 퇴장 시 다른 사람 자동 선택 방지");
                edt(() -> { ((JComboBox<?>) find(panel,"whisperTarget")).setSelectedItem("민수"); return null; });
                set(panel,"whisperInput","성공 확인"); click(panel,"sendWhisper"); requests.poll(5,TimeUnit.SECONDS);
                Message.of("WHISPER_MESSAGE","r1","김지원","민수","성공 확인").write(out);
                await(() -> ui(() -> field(panel,"whisperInput").getText()).isEmpty(), "성공한 귓속말 초안 비우기");
                snapshot(panel,"08-week2-whisper.png");
                click(panel,"leave"); requests.poll(5,TimeUnit.SECONDS);
                Message.of("ROOM_LEFT").write(out);
                await(() -> !ui(() -> find(panel,"whisperComposer").isVisible()), "방 퇴장 시 귓속말 입력 닫기");
                check(!enabled(panel,"sendWhisper"), "퇴장 후 귓속말 비활성화");
            } finally { edt(() -> { panel.close(); return null; }); }
            fixture.get(5,TimeUnit.SECONDS);
        }
    }

    private static void gameUiChecks() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            BlockingQueue<Message> requests = new LinkedBlockingQueue<>();
            CompletableFuture<DataOutputStream> response = new CompletableFuture<>();
            FutureTask<Void> fixture = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    Message.read(in); Message.of("WELCOME","김지원").write(out);
                    Message.of("ROOMS","r1","함께 가위바위보","3").write(out);
                    Message.read(in); Message.of("ROOM_JOINED","r1","함께 가위바위보").write(out);
                    Message.of("ROOM_USERS","r1","김지원","정윤","민수").write(out); response.complete(out);
                    try { while (true) requests.add(Message.read(in)); } catch (EOFException expected) { }
                }
                return null;
            });
            Thread worker = new Thread(fixture,"game-tcp-fixture"); worker.setDaemon(true); worker.start();
            ChatPanel panel = edt(ChatPanel::new);
            try {
                set(panel,"nickname","김지원"); set(panel,"host",server.getInetAddress().getHostAddress());
                set(panel,"port",String.valueOf(server.getLocalPort())); click(panel,"connect");
                await(() -> listSize(panel,"roomList")==1,"게임 TCP 접속");
                selectRoom(panel,"함께 가위바위보"); openSelected(panel);
                await(() -> listSize(panel,"users")==3,"게임 세 명 참가 준비");
                DataOutputStream out = response.get(5,TimeUnit.SECONDS);
                check(!ui(() -> find(panel,"membersPanel").isVisible()),"멤버 패널을 열지 않고 입력창 도구 사용");
                check(!ui(() -> find(panel,"composerTools").isVisible()),"대화 도구는 처음에 접힌 상태");
                set(panel,"messageInput","아직 보내지 않은 이야기");
                click(panel,"toolsToggle");
                check(ui(() -> find(panel,"composerTools").isVisible()),"입력창 더하기 버튼으로 도구 펼치기");
                click(panel,"game");
                check(ui(() -> find(panel,"gamePanel").isVisible()) && !ui(() -> find(panel,"composerTools").isVisible()),"게임을 열면 도구 메뉴 접기");
                check(ui(() -> ((JTextField)find(panel,"messageInput")).getText()).equals("아직 보내지 않은 이야기"),"도구를 열어도 채팅 초안 유지");
                click(panel,"startGame");
                check(Message.of("GAME_START").equals(requests.poll(5,TimeUnit.SECONDS)),"방 전원 게임 시작 요청");
                Message.of("GAME_STARTED","g1","김지원","3","김지원","정윤","민수").write(out);
                await(() -> enabled(panel,"chooseROCK"),"참가자의 선택 버튼 활성화");
                check(!enabled(panel,"startGame"),"진행 중 재시작 차단");
                check(ui(() -> ((JButton)find(panel,"chooseROCK")).getIcon()!=null),"손 모양 선택 버튼 표시");
                snapshot(panel,"11-week2-game-choices.png");
                click(panel,"chooseROCK");
                check(Message.of("GAME_CHOOSE","g1","ROCK").equals(requests.poll(5,TimeUnit.SECONDS)),"게임 ID와 선택 전송");
                Message.of("GAME_CHOSEN","g1","정윤").write(out);
                await(() -> label(panel,"gameStatus").contains("1/3"),"다른 참가자의 선택 완료 표시");
                check(!enabled(panel,"send"),"타인의 선택 완료로 내 요청 대기를 끝내지 않음");
                Message.of("GAME_CHOSEN","g1","김지원").write(out);
                await(() -> enabled(panel,"send"),"내 선택 응답 후 일반 채팅 가능");
                check(!enabled(panel,"choosePAPER"),"선택 후 중복 제출 차단");
                check(ui(() -> {
                    var f=ChatPanel.class.getDeclaredField("timeout"); f.setAccessible(true);
                    return !((javax.swing.Timer)f.get(panel)).isRunning();
                }),"게임 결과 대기 중 7초 연결 종료 타이머 해제");
                check(ui(() -> ((JComponent)find(panel,"gameStage")).getClientProperty("phase")).equals("waiting"),"서버 결과 전에는 대기 연출 유지");
                click(panel,"closeGame");
                check(!gameAnimationRunning(panel),"게임을 접으면 애니메이션 타이머 중지");
                click(panel,"toolsToggle"); click(panel,"game");
                check(gameAnimationRunning(panel),"진행 중 게임을 다시 열면 대기 애니메이션 재개");
                snapshot(panel,"09-week2-game-waiting.png");
                set(panel,"messageInput","게임하면서 대화해요"); click(panel,"send"); requests.poll(5,TimeUnit.SECONDS);
                Message.of("GAME_CHOSEN","g1","민수").write(out);
                Message.of("GAME_RESULT","g1","3","김지원","ROCK","정윤","SCISSORS","민수","SCISSORS","1","김지원").write(out);
                Message.of("EMOTICON_GRANTED","g1","E03").write(out);
                Message.of("EMOTICON_GRANTED","g1","E03").write(out);
                await(() -> textArea(panel).contains("새 이모티콘"),"서버 결과와 보상 수신");
                check(ui(() -> ((JComponent)find(panel,"gameStage")).getClientProperty("phase")).equals("reveal"),"서버 결과 수신 후에만 공개 애니메이션 시작");
                await(() -> ui(() -> ((JComponent)find(panel,"gameStage")).getClientProperty("phase")).equals("result"),"짧은 공개 연출 후 참가자별 결과 카드 표시");
                check(label(panel,"gameStatus").equals("승리 · 김지원"),"서버가 보낸 승자 표시");
                check(!gameAnimationRunning(panel),"결과 공개 후 타이머 종료");
                snapshot(panel,"12-week2-game-result.png");
                check(textArea(panel).split("새 이모티콘",-1).length==2,"중복 보상 이벤트 안내 1회");
                check(!enabled(panel,"send"),"게임 결과와 보상이 채팅 응답 대기를 지우지 않음");
                Message.of("CHAT_MESSAGE","r1","김지원","게임하면서 대화해요").write(out);
                await(() -> enabled(panel,"emoticons"),"채팅 응답 후 이모티콘 열기 가능");
                click(panel,"toolsToggle"); click(panel,"emoticons");
                check(!ui(() -> find(panel,"composerTools").isVisible()) && ui(() -> find(panel,"emoticonPicker").isVisible()),"입력창 이모티콘 버튼은 도구 메뉴와 겹치지 않음");
                check(Message.of("EMOTICON_LIST").equals(requests.poll(5,TimeUnit.SECONDS)),"보유 이모티콘 목록 요청");
                Message.of("EMOTICON_OWNED","E03").write(out);
                await(() -> enabled(panel,"emoticonE03"),"보유 이모티콘 활성화");
                check(!enabled(panel,"emoticonE01"),"미보유 이모티콘 전송 차단");
                click(panel,"emoticonE03");
                check(Message.of("EMOTICON_SEND","E03").equals(requests.poll(5,TimeUnit.SECONDS)),"이모티콘 ID 전송");
                Message.of("EMOTICON_MESSAGE","r1","정윤","E03").write(out);
                await(() -> textArea(panel).contains("축하해요!"),"다른 사람의 이모티콘 수신");
                check(!enabled(panel,"send"),"타인의 이모티콘으로 내 전송 대기 종료하지 않음");
                Message.of("EMOTICON_MESSAGE","r1","김지원","E03").write(out);
                await(() -> enabled(panel,"send"),"내 이모티콘 전송 확인");
                snapshot(panel,"10-week2-game-reward.png");
                click(panel,"closeEmoticons");
                Message.of("GAME_STARTED","g2","정윤","2","정윤","민수").write(out);
                await(() -> label(panel,"gameStatus").contains("0/2"),"내 요청 없이 시작된 게임 수신");
                check(!enabled(panel,"chooseROCK"),"참가자 목록에 없는 중도 입장자는 관전만");
                Message.of("GAME_RESULT","g2","2","정윤","ROCK","민수","ROCK","0").write(out);
                await(() -> label(panel,"gameStatus").contains("무승부"),"무승부 표시");
                Message.of("GAME_STARTED","g3","정윤","3","김지원","정윤","민수").write(out);
                Message.of("ROOM_USERS","r1","김지원","정윤","새친구").write(out);
                await(() -> label(panel,"gameStatus").contains("0/2"),"퇴장 참가자 제거와 새 참가자 자동 편입 방지");
                Message.of("GAME_CANCELED","g3","PLAYERS_LEFT").write(out);
                await(() -> textArea(panel).contains("참가자가 부족"),"참가자 이탈 취소 표시");
                Message.of("GAME_STARTED","g4","김지원","2","김지원","정윤").write(out);
                Message.of("GAME_CANCELED","g4","TIMEOUT").write(out);
                await(() -> textArea(panel).contains("선택한 사람이 부족"),"서버 시간 초과 취소 표시");
                Message.of("GAME_RESULT","g5","3","김지원","ROCK","정윤","ROCK","민수","SCISSORS","2","김지원","정윤").write(out);
                await(() -> label(panel,"gameStatus").equals("승리 · 김지원, 정윤"),"중도 입장 관전 결과와 복수 승자 표시");
                Message.of("GAME_RESULT","g6","2","김지원","ROCK","정윤","SCISSORS","1","김지원").write(out);
                await(() -> ui(() -> ((JComponent)find(panel,"gameStage")).getClientProperty("phase")).equals("reveal"),"다음 결과 공개 도중 퇴장 준비");
                click(panel,"leave"); requests.poll(5,TimeUnit.SECONDS); Message.of("ROOM_LEFT").write(out);
                await(() -> !ui(() -> find(panel,"gamePanel").isVisible()),"퇴장 시 게임 화면 초기화");
                check(!gameAnimationRunning(panel),"결과 연출 중 퇴장하면 타이머 정리");
                check(ui(() -> ((JComponent)find(panel,"gameStage")).getClientProperty("phase")).equals("idle"),"퇴장 뒤 이전 결과 연출 제거");
                Message.of("EMOTICON_GRANTED","g5","E08").write(out);
                await(() -> ui(() -> ((JButton)find(panel,"emoticons")).getText()).contains("새 보상"),"방 밖에서 도착한 보상 이벤트 처리");
                selectRoom(panel,"함께 가위바위보"); openSelected(panel); requests.poll(5,TimeUnit.SECONDS);
                Message.of("ROOM_JOINED","r1","함께 가위바위보").write(out);
                Message.of("ROOM_USERS","r1","김지원").write(out);
                await(() -> listSize(panel,"users")==1,"혼자 있는 방 입장");
                check(!enabled(panel,"startGame"),"한 명일 때 시작 차단");
                Message.of("GAME_RESULT","bad","999999999","0").write(out);
                await(() -> status(panel).contains("응답 형식"),"잘못된 반복 필드 수 안전하게 거부");
            } finally { edt(() -> { panel.close(); return null; }); }
            fixture.get(5,TimeUnit.SECONDS);
        }
    }

    private static void animatedDemoChecks() throws Exception {
        ChatPanel panel=edt(ChatPanel::new);
        try {
            set(panel,"nickname","김지원"); click(panel,"demo");
            await(() -> listSize(panel,"roomList")==2,"서버 없이 애니메이션 데모 접속");
            selectRoom(panel,"우리들의 아지트"); openSelected(panel);
            await(() -> listSize(panel,"users")==3,"가상 친구 두 명과 체험방 입장");
            click(panel,"toolsToggle"); snapshot(panel,"13-week2-composer-tools.png");
            click(panel,"game"); click(panel,"startGame");
            await(() -> enabled(panel,"choosePAPER"),"서버 없이 게임 시작");
            snapshot(panel,"14-week2-game-small.png",960,680);
            click(panel,"membersToggle"); snapshot(panel,"15-week2-game-members-small.png",960,680);
            click(panel,"membersToggle"); click(panel,"choosePAPER");
            await(() -> label(panel,"gameStatus").equals("승리 · 김지원"),"가상 친구 응답과 결과 공개 완료");
            click(panel,"emoticons");
            await(() -> enabled(panel,"emoticonE03"),"데모 승리 보상 사용 가능");
            check(!ui(() -> find(panel,"gamePanel").isVisible()),"그림 선택창을 열면 게임 카드를 접어 대화 공간 확보");
            check(ui(() -> ((JButton)find(panel,"emoticonE03")).getIcon()!=null),"보유 목록에 그림 이모티콘 표시");
            check(!enabled(panel,"emoticonE08"),"그림으로 바꿔도 미보유 항목 전송 차단");
            snapshot(panel,"16-week2-sticker-picker.png",960,680);
            click(panel,"emoticonE03");
            await(() -> textArea(panel).contains("축하해요!"),"데모 이모티콘 전송");
            await(() -> enabled(panel,"emoticonE01"),"그림 송신 확인 이후 다시 선택 가능");
            click(panel,"emoticonE01");
            await(() -> textArea(panel).contains("[이모티콘: 미소]"),"그림 메시지 복사용 설명 보존");
            snapshot(panel,"17-week2-sticker-chat.png",960,680);
            click(panel,"closeEmoticons");
            click(panel,"toolsToggle"); click(panel,"whisper");
            edt(() -> { ((JComboBox<?>)find(panel,"whisperTarget")).setSelectedIndex(0); return null; });
            set(panel,"whisperInput","반가워요"); click(panel,"sendWhisper");
            await(() -> textArea(panel).contains("데모 자동 응답"),"가상 친구의 귓속말 응답");
            click(panel,"closeWhisper"); click(panel,"startGame");
            await(() -> enabled(panel,"chooseROCK"),"데모 다음 판 시작");
            click(panel,"chooseROCK");
            await(() -> enabled(panel,"leave"),"선택 응답 이후 퇴장 가능");
            click(panel,"leave");
            await(() -> status(panel).contains("로비로"),"가상 응답 대기 중 퇴장");
            selectRoom(panel,"오늘 뭐 먹지?"); openSelected(panel);
            await(() -> label(panel,"roomTitle").equals("오늘 뭐 먹지?"),"다른 데모 방 입장");
            Thread.sleep(1700);
            check(!ui(() -> find(panel,"gamePanel").isVisible()) && !textArea(panel).contains("승리"),"이전 방의 지연된 가상 게임 응답 차단");
        } finally { edt(() -> { panel.close(); return null; }); }
    }

    private static void stickerRenderingChecks() throws Exception {
        edt(() -> {
            ConversationView view=new ConversationView();
            JPanel parent=new JPanel(new BorderLayout()); parent.setSize(400,600); parent.add(view); parent.doLayout();
            view.addEmoticon("지원","E03",true); view.addEmoticon("정윤","E04",false);
            int before=view.getPreferredSize().height;
            check(before>=224,"양쪽 그림 메시지에 충분한 높이 확보");
            view.addMessage("정윤","E03은 일반 텍스트예요",false);
            check(view.getPreferredSize().height>before,"그림 뒤 일반 메시지의 공간 분리");
            check(view.getText().contains("[이모티콘: 축하해요!]") && view.getText().contains("E03은 일반 텍스트예요"),"그림과 일반 텍스트를 구분하여 복사");
            check(view.getAccessibleContext().getAccessibleDescription().contains("고마워요"),"그림의 의미를 접근성 설명에 제공");
            boolean rejected=false;
            try { view.addEmoticon("지원","BAD",true); } catch(IllegalArgumentException e) { rejected=true; }
            check(rejected,"알 수 없는 그림 ID 거부");
            view.setSize(400,view.getPreferredSize().height);
            BufferedImage image=new BufferedImage(400,view.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); view.paint(g); g.dispose();
            ImageIO.write(image,"png",Path.of("build/screenshots/18-week2-sticker-messages.png").toFile());
            view.clear(); check(view.getText().isEmpty(),"방 초기화 시 그림 메시지도 제거");
            return null;
        });
    }

    private static boolean gameAnimationRunning(ChatPanel panel) throws Exception {
        return edt(() -> {
            Component stage=find(panel,"gameStage");
            var field=stage.getClass().getDeclaredField("timer"); field.setAccessible(true);
            return ((javax.swing.Timer)field.get(stage)).isRunning();
        });
    }

    private static void snapshot(ChatPanel panel, String name) throws Exception { snapshot(panel,name,1080,720); }
    private static void snapshot(ChatPanel panel, String name, int width, int height) throws Exception {
        edt(() -> {
            panel.setSize(width, height); layout(panel);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
            Path directory = Path.of("build/screenshots"); Files.createDirectories(directory);
            ImageIO.write(image, "png", directory.resolve(name).toFile());
            return null;
        });
    }
    private static void layout(Container parent) {
        parent.doLayout();
        for (Component component : parent.getComponents()) if (component instanceof Container child) layout(child);
    }
    private static Component find(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) { Component found = find(child, name); if (found != null) return found; }
        }
        return null;
    }
    private static boolean active(Component component) {
        for(Component current=component; current!=null; current=current.getParent()) if(!current.isVisible()) return false;
        return true;
    }
    private static JTextField field(ChatPanel panel, String name) { return (JTextField) find(panel, name); }
    private static void selectRoom(ChatPanel panel, String name) throws Exception {
        edt(() -> {
            JList<?> list = (JList<?>) find(panel,"roomList");
            for (int i=0;i<list.getModel().getSize();i++) if (list.getModel().getElementAt(i).toString().equals(name)) { list.setSelectedIndex(i); return null; }
            throw new AssertionError("대화를 찾을 수 없음: "+name);
        });
    }
    private static void openSelected(ChatPanel panel) throws Exception {
        edt(() -> { ((JList<?>)find(panel,"roomList")).getActionMap().get("openRoom").actionPerformed(new java.awt.event.ActionEvent(panel,0,"openRoom")); return null; });
    }
    private static void set(ChatPanel panel, String name, String text) throws Exception { edt(() -> { field(panel, name).setText(text); return null; }); }
    private static void click(ChatPanel panel, String name) throws Exception { edt(() -> { ((JButton) find(panel, name)).doClick(); return null; }); }
    private static String label(ChatPanel panel, String name) { return ui(() -> ((JLabel) find(panel, name)).getText()); }
    private static String status(ChatPanel panel) { return label(panel, "status"); }
    private static boolean enabled(ChatPanel panel, String name) { return ui(() -> find(panel, name).isEnabled()); }
    private static int listSize(ChatPanel panel, String name) { return ui(() -> ((JList<?>) find(panel, name)).getModel().getSize()); }
    private static String textArea(ChatPanel panel) { return ui(() -> ((ConversationView) find(panel, "transcript")).getText()); }
    private static <T> T ui(Callable<T> work) {
        try { return edt(work); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static <T> T edt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return work.call();
        FutureTask<T> task = new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void await(BooleanSupplier condition, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), name);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
}
