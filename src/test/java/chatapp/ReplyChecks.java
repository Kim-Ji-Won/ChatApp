package chatapp;

import chatapp.client.network.ChatTransport;
import chatapp.client.network.DemoTransport;
import chatapp.client.ui.ChatPanel;
import chatapp.client.ui.ConversationView;
import chatapp.common.Message;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JTextField;
import static chatapp.Week3Checks.*;

public final class ReplyChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        demoHistory();
        arrowLayout();
        quoteNavigation();
        socketUi();
        System.out.println("PASS WEEK4: " + checks + " checks");
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static void demoHistory() throws Exception {
        var events = new ArrayList<Message>();
        try (var demo = new DemoTransport()) {
            demo.connect("", 0, "지원", new ChatTransport.Listener() {
                public void onMessage(Message message) { events.add(message); }
                public void onDisconnected(String reason) {}
            });
            demo.send(Message.of("ROOM_JOIN", "1"));
            Message greeting = events.stream().filter(m -> m.type().equals("CHAT_MESSAGE")).findFirst().orElseThrow();
            check(greeting.field(0).equals("1") && greeting.field(1).equals("데모 친구"), "아지트에 입장하면 친구의 일반 말풍선 수신");
            demo.send(Message.of("CHAT_REPLY_SEND", id(), "1", greeting.field(3), "일곱 시 어때?"));
            Message quoted = events.get(events.size()-1);
            check(quoted.type().equals("CHAT_REPLY_MESSAGE") && quoted.field(3).equals("지원")
                    && quoted.fields().subList(5, 8).equals(java.util.List.of(greeting.field(3), "데모 친구", greeting.field(2))),
                    "데모 친구 원문을 인용해 내 답장 전송");
            demo.send(Message.of("ROOM_LEAVE"));
            demo.send(Message.of("ROOM_JOIN", "2"));
            demo.send(Message.of("CHAT_SEND", "저녁 약속"));
            Message source = events.get(events.size()-1);
            Message request = Message.of("CHAT_REPLY_SEND", id(), "2", source.field(3), "좋아요");
            demo.send(request);
            Message reply = events.get(events.size()-1);
            check(reply.equals(Message.of("CHAT_REPLY_MESSAGE", "2", request.field(0), reply.field(2), "지원",
                    "좋아요", source.field(3), "지원", "저녁 약속")), "데모 답장 필드와 원문 사본");
            demo.send(request);
            check(events.get(events.size()-1).equals(reply), "중복 성공 재전송");
            demo.send(Message.of("CHAT_REPLY_SEND", id(), "2", reply.field(2), "중첩"));
            check(events.get(events.size()-1).field(0).equals("INVALID_REPLY_TARGET"), "답장에 답장 거부");
            demo.send(Message.of("ROOM_LEAVE"));
            demo.send(request);
            check(events.get(events.size()-1).field(0).equals("NOT_IN_ROOM"), "방 밖에서 재전송 거부");
            demo.send(Message.of("ROOM_JOIN", "2"));
            for (int i = 0; i < 200; i++) demo.send(Message.of("CHAT_SEND", "일반 메시지"));
            demo.send(Message.of("CHAT_REPLY_SEND", id(), "2", source.field(3), "답장"));
            check(events.get(events.size()-1).field(0).equals("REPLY_TARGET_NOT_FOUND"), "보관 범위를 지난 원문 거부");
        }
    }

    private static void quoteNavigation() throws Exception {
        edt(() -> {
            var view = new ConversationView();
            var pane = new javax.swing.JScrollPane(view);
            pane.setSize(640, 220);
            pane.doLayout();
            view.attachScrollPane(pane, unseen -> {});
            int width = pane.getViewport().getExtentSize().width;
            view.setSize(width, 220);
            boolean[] missed = {false};
            view.setReplyActions(quote -> {}, () -> missed[0] = true);
            String original = id();
            view.addChat("정윤", "같은 본문", false, original, null);
            for (int i = 0; i < 12; i++) view.addChat("지원", "같은 본문", true, id(), null);
            // 직전 말풍선과 연속되지 않게 해 마지막 인용 영역의 시작 위치를 고정한다.
            view.addNotice("중간 안내");
            for (String target : new String[]{original, id()}) {
                int quoteTop = view.getPreferredSize().height - 18;
                view.addChat("지원", "확인했어", true, id(), new ConversationView.Quote(target, "정윤", "같은 본문"));
                view.setSize(width, view.getPreferredSize().height);
                pane.getViewport().setViewPosition(new java.awt.Point(0, quoteTop - 100));
                int x = width - 36, y = quoteTop + 16;
                view.dispatchEvent(new MouseEvent(view, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1));
                view.dispatchEvent(new MouseEvent(view, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1));
                if (target.equals(original)) {
                    check(pane.getViewport().getViewPosition().y < 50 && !missed[0], "같은 본문 중 ID가 일치하는 원문으로 이동");
                } else {
                    check(missed[0], "원문이 화면에 없으면 안내");
                }
                for (var component : view.getComponentPopupMenu().getComponents()) {
                    if ("messageToTodo".equals(component.getName())) check(!component.isEnabled(), "답장 말풍선의 할 일 생성 제한");
                }
                view.addNotice("다음 안내");
            }
            return null;
        });
    }

    private static void socketUi() throws Exception {
        ChatPanel panel = edt(ChatPanel::new);
        try (var peer = new TestPeer()) {
            set(panel, "nickname", "지원"); set(panel, "port", Integer.toString(peer.port()));
            click(panel, "connect"); peer.accept(); peer.take();
            peer.send(Message.of("WELCOME", "지원"), Message.of("ROOMS", "r", "대화방", "2"));
            waitFor(() -> size(panel, "roomList") == 1, "방 목록");
            openRoom(panel, 0); peer.take();
            String sourceId = id();
            peer.send(Message.of("ROOM_JOINED", "r", "대화방"), Message.of("CHAT_MESSAGE", "r", "정윤", "저녁 약속", sourceId));
            ConversationView view = edt(() -> (ConversationView)find(panel, "transcript"));
            waitFor(() -> ui(() -> view.getText().contains("저녁 약속")), "일반 메시지 수신");
            snapshot(panel, "week4-reply-idle", 960, 680);
            edt(() -> { moveOver(view, 85, 45); return null; });
            snapshot(panel, "week4-reply-hover", 960, 680);
            selectFirstBubble(view);
            set(panel, "messageInput", "　  ");
            check(!enabled(panel, "send"), "인용을 선택해도 공백 본문은 전송 불가");
            edt(() -> { ((JTextField)find(panel, "messageInput")).postActionEvent(); return null; });
            check(peer.noRequest() && visible(panel, "replyPreview"), "공백 Enter는 요청 없이 인용 유지");
            set(panel, "messageInput", "좋아요");
            click(panel, "cancelReply");
            check(!visible(panel, "replyPreview") && text(panel, "messageInput").equals("좋아요"), "답장 취소 후 본문 유지");
            selectFirstBubble(view);
            snapshot(panel, "week4-reply-compose", 960, 680);
            click(panel, "send");
            Message first = peer.take();
            check(first.equals(Message.of("CHAT_REPLY_SEND", first.field(0), "r", sourceId, "좋아요")), "인용 원문 ID로 전송");
            peer.send(Message.of("ERROR", "INVALID_REPLY", "늦은 오류", id()),
                    Message.of("SYSTEM", "r", "도착 확인"));
            waitFor(() -> ui(() -> view.getText().contains("도착 확인")), "관련 없는 오류 처리 뒤에도 연결 유지");
            check(!enabled(panel,"send"), "다른 요청 오류가 전송 대기를 해제하지 않음");
            peer.send(Message.of("ERROR", "REPLY_TARGET_NOT_FOUND", "원문 없음", first.field(0)));
            waitFor(() -> enabled(panel, "send"), "일치하는 오류에서 작성 복귀");
            check(visible(panel, "replyPreview") && text(panel,"messageInput").equals("좋아요"), "실패 후 원문과 본문 보존");
            click(panel, "send"); Message retry = peer.take();
            set(panel, "messageInput", "다음 초안");
            String replyId = id();
            Message reply = Message.of("CHAT_REPLY_MESSAGE","r",retry.field(0),replyId,"지원","좋아요",sourceId,"정윤","저녁 약속");
            peer.send(Message.of("CHAT_REPLY_MESSAGE","other",retry.field(0),id(),"지원","다른 방 답장",sourceId,"정윤","저녁 약속"),
                    reply, reply, Message.of("SYSTEM","r","재전송 확인"));
            waitFor(() -> ui(() -> view.getText().contains("재전송 확인")), "답장 연속 수신");
            String history = edt(view::getText);
            check(!history.contains("다른 방 답장") && history.indexOf("좋아요") == history.lastIndexOf("좋아요")
                    && history.contains("↳ 정윤: 저녁 약속"), "다른 방 격리와 동일 ID 중복 표시 차단");
            check(!visible(panel,"replyPreview") && text(panel,"messageInput").equals("다음 초안"), "내 답장 응답 후 새 초안 보존");
            snapshot(panel, "week4-reply-received", 960, 680);
        } finally { edt(() -> { panel.close(); return null; }); }
    }

    private static void selectFirstBubble(ConversationView view) throws Exception {
        edt(() -> {
            view.setSize(620, 500);
            view.doLayout();
            moveOver(view, 85, 45);
            JButton arrow = (JButton) view.getComponent(0);
            int x = arrow.getX() + arrow.getWidth()/2, y = arrow.getY() + arrow.getHeight()/2;
            check(view.getComponentAt(x, y) == arrow, "표시된 화살표의 클릭 영역");
            for (int type : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED}) {
                arrow.dispatchEvent(new MouseEvent(arrow, type, System.currentTimeMillis(), 0,
                        x-arrow.getX(), y-arrow.getY(), 1, false, MouseEvent.BUTTON1));
            }
            return null;
        });
    }

    private static void moveOver(ConversationView view, int x, int y) {
        view.dispatchEvent(new MouseEvent(view, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false));
    }

    private static void arrowLayout() throws Exception {
        edt(() -> {
            ConversationView view = new ConversationView();
            var chosen = new ArrayList<ConversationView.Quote>();
            view.setReplyActions(chosen::add, () -> {});
            String other = id(), mine = id();
            view.addChat("정윤", "오늘 만날까?", false, other, null);
            view.addChat("지원", "오늘 만날까?", true, mine, null);
            view.addChat("지원", "좋아", true, id(), new ConversationView.Quote(other, "정윤", "오늘 만날까?"));
            view.addNotice("안내"); view.addMessage("귓속말", "비공개", false);
            view.addTaskMessage("구버전", "ID 없음", false); view.addEmoticon("정윤", "E01", false);
            check(view.getComponentCount() == 2, "ID가 있는 일반 말풍선에만 화살표 표시");
            for (int width : new int[]{620, 360}) {
                view.setSize(width, 640); view.doLayout();
                var left = (JButton)view.getComponent(0);
                var right = (JButton)view.getComponent(1);
                check(!left.isVisible() && !right.isVisible(), "기본 화면에서 답장 버튼 숨김");
                int height = view.getPreferredSize().height;
                moveOver(view, 84, left.getY()+10);
                check(left.isVisible() && !right.isVisible() && left.getX() > 84, "상대 말풍선 오른쪽에만 답장 표시");
                moveOver(view, left.getX()-2, left.getY()+10);
                check(left.isVisible(), "말풍선과 버튼 사이 여백에서 표시 유지");
                left.dispatchEvent(new MouseEvent(left, MouseEvent.MOUSE_ENTERED, 0, 0, 10, 10, 0, false));
                check(left.isVisible(), "버튼에 진입해도 답장 유지");
                left.doClick();
                moveOver(view, width-40, right.getY()+10);
                check(right.isVisible() && !left.isVisible() && right.getX()+right.getWidth() < width-40,
                        "내 말풍선 왼쪽으로 답장 버튼 전환");
                right.doClick();
                moveOver(view, 5, 5);
                check(!left.isVisible() && !right.isVisible() && view.getPreferredSize().height == height,
                        "대화를 벗어나면 행 간격 변경 없이 숨김");
                check(chosen.get(0).id().equals(other) && chosen.get(1).id().equals(mine), "같은 문구도 누른 화살표의 원문 선택");
                chosen.clear();
            }
            view.clear();
            check(view.getComponentCount() == 0, "방 전환 시 답장 버튼 정리");
            return null;
        });
    }

    private static void waitFor(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), description);
    }
    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
        checks++; System.out.println("PASS " + description);
    }
}
