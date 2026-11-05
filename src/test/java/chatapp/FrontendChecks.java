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
    private static void snapshot(ChatPanel panel, String name) throws Exception {
        edt(() -> {
            panel.setSize(1080, 720); layout(panel);
            BufferedImage image = new BufferedImage(1080, 720, BufferedImage.TYPE_INT_RGB);
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
