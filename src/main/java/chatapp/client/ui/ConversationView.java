package chatapp.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.text.AttributedString;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 대화 내용을 평문으로 보관하고 가변 너비 말풍선으로 표시한다. 시각은 이 클라이언트 수신 시각. */
public final class ConversationView extends JComponent implements Scrollable {
    private record Entry(String sender, String text, boolean mine, boolean notice, String time, long arrivedAt, String emoticonId, boolean taskable, String messageId, Quote quote) { }
    public record Quote(String id, String sender, String text) {}
    private Consumer<Quote> replyHandler = quote -> {};
    private Runnable missingSource = () -> {};
    private String highlightedId;
    private final Timer highlightTimer = new Timer(1400, event -> {
        highlightedId = null;
        repaint();
    });

    public void setReplyActions(Consumer<Quote> select, Runnable missing) {
        replyHandler = select;
        missingSource = missing;
    }

    private record Lines(List<TextLayout> layouts, float width, float height) { }
    private static final Font BODY = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font SMALL = new Font("SansSerif", Font.PLAIN, 11);
    private static final FontRenderContext METRICS = new FontRenderContext(null, true, true);
    private final List<Entry> entries = new ArrayList<>();
    private JScrollPane scrollPane;
    private Consumer<Boolean> unseenListener = value -> { };
    private boolean unseen;
    private boolean scrollPending;
    private boolean followLatest;
    private int anchorY;
    private long revision;
    private java.util.function.BiConsumer<String,String> taskHandler=(from,text)->{};
    private final List<Rectangle> messageBounds=new ArrayList<>();
    private Entry selectedMessage;
    private final JMenuItem task = new JMenuItem("할 일로 만들기");
    private final Map<String, JButton> replyButtons = new HashMap<>();
    private JButton hoverButton;
    private final java.awt.event.MouseAdapter replyHover = new java.awt.event.MouseAdapter() {
        private void track(java.awt.event.MouseEvent event) {
            Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), ConversationView.this);
            updateReplyHover(point);
        }
        @Override public void mouseEntered(java.awt.event.MouseEvent event) { track(event); }
        @Override public void mouseMoved(java.awt.event.MouseEvent event) { track(event); }
        @Override public void mouseExited(java.awt.event.MouseEvent event) { track(event); }
    };
    private static final int REPLY_GAP = 5;
    private static final int REPLY_WIDTH = 34;
    private static final int REPLY_HEIGHT = 26;
    public void setTaskHandler(java.util.function.BiConsumer<String,String> handler) { taskHandler=handler; }


    public ConversationView() {
        setName("transcript"); setOpaque(true); setBackground(Theme.CHAT);
        setToolTipText("메시지 옆 시간은 이 컴퓨터에서 받은 시각입니다.");
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("대화 내용 복사");
        copy.addActionListener(event -> getToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(getText()), null));
        task.setName("messageToTodo");
        task.addActionListener(event -> { if(selectedMessage!=null && selectedMessage.taskable) taskHandler.accept(selectedMessage.sender,selectedMessage.text); });
        highlightTimer.setRepeats(false);
        addMouseListener(replyHover);
        addMouseMotionListener(replyHover);
        menu.add(task); menu.add(copy); setComponentPopupMenu(menu);
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || selectedMessage == null || selectedMessage.quote == null) return;
                int index = entries.indexOf(selectedMessage);
                if (index < 0) return;
                Rectangle box = messageBounds.get(index);
                if (box.contains(event.getPoint()) && event.getY() < box.y + QUOTE_HEIGHT) {
                    jumpToSource(selectedMessage.quote.id);
                }
            }
        });
    }

    @Override protected void processMouseEvent(java.awt.event.MouseEvent event) {
        if (event.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED || event.isPopupTrigger()) {
            selectAt(event.getPoint());
        }
        super.processMouseEvent(event);
    }

    @Override public Point getPopupLocation(java.awt.event.MouseEvent event) {
        if (event != null) selectAt(event.getPoint());
        return super.getPopupLocation(event);
    }

    private void selectAt(Point point) {
        render(null, getWidth());
        selectedMessage = null;
        for (int i = 0; i < messageBounds.size(); i++) {
            if (messageBounds.get(i).contains(point)) {
                selectedMessage = entries.get(i);
                break;
            }
        }
        boolean normal = selectedMessage != null && selectedMessage.taskable;
        task.setEnabled(normal);
    }

    public void addMessage(String sender, String text, boolean mine) {
        append(new Entry(sender, text, mine, false, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), System.currentTimeMillis(), null, false, null, null));
    }
    public void addTaskMessage(String sender,String text,boolean mine) {
        addChat(sender, text, mine, null, null);
    }
    public boolean addChat(String sender, String text, boolean mine, String id, Quote quote) {
        if (id != null && entries.stream().anyMatch(entry -> id.equals(entry.messageId))) return false;
        if (id != null && quote == null) {
            JButton button = createReplyButton(new Quote(id, sender, text));
            replyButtons.put(id, button);
            add(button);
        }
        append(new Entry(sender, text, mine, false, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                System.currentTimeMillis(), null, quote == null, id, quote));
        return true;
    }
    private void updateReplyHover(Point point) {
        JButton next = null;
        if (point != null) {
            for (JButton button : replyButtons.values()) {
                Rectangle bubble = (Rectangle) button.getClientProperty("sourceBounds");
                if (bubble == null) continue;
                if (bubble.contains(point) || (button == hoverButton && bubble.union(button.getBounds()).contains(point))) {
                    next = button;
                    break;
                }
            }
        }
        if (next == hoverButton) return;
        if (hoverButton != null) hoverButton.setVisible(false);
        hoverButton = next;
        if (next != null) next.setVisible(true);
        repaint();
    }

    private JButton createReplyButton(Quote target) {
        JButton button = new JButton(new Icon() {
            public int getIconWidth() { return 18; }
            public int getIconHeight() { return 16; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x34464D));
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D arrow = new Path2D.Float();
                arrow.moveTo(2.5, 2); arrow.lineTo(2.5, 8);
                arrow.quadTo(2.5, 11.5, 6, 11.5); arrow.lineTo(15, 11.5);
                arrow.moveTo(11.5, 8); arrow.lineTo(15, 11.5); arrow.lineTo(11.5, 15);
                g.draw(arrow);
                g.dispose();
            }
        }) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = getModel().isRollover() || isFocusOwner() ? Color.WHITE : new Color(0xF2F6F8);
                g.setColor(getModel().isPressed() ? new Color(0xD2E1E6) : fill);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                getIcon().paintIcon(this, g, (getWidth()-getIcon().getIconWidth())/2,
                        (getHeight()-getIcon().getIconHeight())/2);
                g.dispose();
            }
        };
        button.setName("messageReply");
        button.setVisible(false);
        button.addMouseListener(replyHover);
        button.addMouseMotionListener(replyHover);
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText("답장하기");
        button.getAccessibleContext().setAccessibleName(target.sender() + " 메시지에 답장");
        button.getAccessibleContext().setAccessibleDescription(target.text());
        button.addActionListener(event -> replyHandler.accept(target));
        return button;
    }

    private void jumpToSource(String id) {
        render(null, getWidth());
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).messageId)) {
                revision++;
                scrollPending = false;
                scrollRectToVisible(messageBounds.get(i));
                highlightedId = id;
                highlightTimer.restart();
                repaint();
                return;
            }
        }
        missingSource.run();
    }
    public void addEmoticon(String sender, String id, boolean mine) {
        try { EmoticonPicker.validate(id); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e.getMessage(), e); }
        // 복사·접근성용 설명과 그림 ID를 함께 보관한다. 일반 채팅 문자열은 그림으로 해석하지 않는다.
        append(new Entry(sender, "[이모티콘: " + EmoticonPicker.label(id) + "]", mine, false,
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), System.currentTimeMillis(), id, false, null, null));
    }
    public void addNotice(String text) { append(new Entry("", text, false, true, "", System.currentTimeMillis(), null, false, null, null)); }
    public void clear() {
        hoverButton = null; removeAll(); replyButtons.clear();
        highlightTimer.stop(); highlightedId = null; revision++; scrollPending=false; entries.clear(); selectedMessage=null; messageBounds.clear(); setUnseen(false);
        if (scrollPane!=null) scrollPane.getViewport().setViewPosition(new Point());
        revalidate(); repaint();
    }
    public void attachScrollPane(JScrollPane pane, Consumer<Boolean> listener) {
        scrollPane=pane; unseenListener=listener;
        pane.getViewport().addChangeListener(event -> {
            if (!scrollPending && nearBottom()) setUnseen(false);
            if (isShowing() && !GraphicsEnvironment.isHeadless()) updateReplyHover(getMousePosition(true));
        });
        listener.accept(unseen);
    }
    public boolean hasUnseenMessages() { return unseen; }
    public void jumpToLatest() {
        scheduleScroll(); followLatest=true; setUnseen(false);
    }
    private boolean nearBottom() {
        if (scrollPane==null) return true;
        JViewport viewport=scrollPane.getViewport();
        return viewport.getExtentSize().height==0 ||
                viewport.getViewPosition().y+viewport.getExtentSize().height >= getPreferredSize().height-28;
    }
    private void setUnseen(boolean value) {
        if (unseen!=value) { unseen=value; unseenListener.accept(value); }
    }
    public String getText() {
        StringBuilder text = new StringBuilder();
        for (Entry e : entries) {
            text.append(e.notice ? "[안내]" : e.sender).append('\n');
            if (e.quote != null) text.append("↳ ").append(e.quote.sender).append(": ").append(e.quote.text).append('\n');
            text.append(e.text).append("\n\n");
        }
        return text.toString();
    }
    private void append(Entry entry) {
        // 변경 전 위치를 잡고 같은 EDT 배치의 메시지들은 한 번에 스크롤 처리한다.
        scheduleScroll();
        entries.add(entry);
        if (entries.size()>300) {
            int before=getPreferredSize().height;
            Entry oldest = entries.remove(0);
            JButton retired = replyButtons.remove(oldest.messageId);
            if (retired != null) {
                if (retired == hoverButton) hoverButton = null;
                remove(retired);
            }
            anchorY=Math.max(0,anchorY-(before-getPreferredSize().height));
        }
        revalidate(); repaint();
    }
    private void scheduleScroll() {
        if (scrollPending) return;
        followLatest=nearBottom();
        anchorY=scrollPane==null ? 0 : scrollPane.getViewport().getViewPosition().y;
        scrollPending=true;
        long token=revision;
        SwingUtilities.invokeLater(() -> {
            if (token!=revision) return;
            if (scrollPane!=null) {
                scrollPane.doLayout();
                JViewport viewport=scrollPane.getViewport();
                Dimension extent=viewport.getExtentSize();
                int height=Math.max(extent.height,getPreferredSize().height);
                viewport.setViewSize(new Dimension(extent.width,height));
                int bottom=Math.max(0,height-extent.height);
                viewport.setViewPosition(new Point(0,followLatest ? bottom : Math.min(anchorY,bottom)));
            }
            scrollPending=false;
            setUnseen(!followLatest && !nearBottom());
        });
    }
    private int availableWidth() { return getParent() == null ? 720 : Math.max(260, getParent().getWidth()); }
    private static Lines wrap(String text, float maxWidth, Font font) {
        List<TextLayout> layouts = new ArrayList<>(); float width = 0, height = 0;
        for (String paragraph : text.split("\n", -1)) {
            AttributedString value = new AttributedString(paragraph.isEmpty() ? " " : paragraph);
            value.addAttribute(TextAttribute.FONT, font);
            LineBreakMeasurer measurer = new LineBreakMeasurer(value.getIterator(), METRICS);
            while (measurer.getPosition() < value.getIterator().getEndIndex()) {
                TextLayout layout = measurer.nextLayout(maxWidth);
                layouts.add(layout); width = Math.max(width, layout.getAdvance());
                height += layout.getAscent()+layout.getDescent()+layout.getLeading()+5;
            }
        }
        // 줄 간격은 줄 사이에만 두고 마지막 줄 아래에는 추가하지 않는다.
        return new Lines(layouts, width, Math.max(0, height-5));
    }
    private static final int QUOTE_HEIGHT = 44;
    private static String fit(String value, int pixels, FontMetrics metrics) {
        if (metrics.stringWidth(value) <= pixels) return value;
        int width = metrics.stringWidth("…");
        int end = 0;
        while (end < value.length()) {
            int cp = value.codePointAt(end);
            width += metrics.charWidth(cp);
            if (width > pixels) break;
            end += Character.charCount(cp);
        }
        return value.substring(0, end) + "…";
    }
    @Override public void doLayout() {
        render(null, getWidth(), true);
    }

    private int render(Graphics2D graphics, int width) {
        return render(graphics, width, false);
    }

    private int render(Graphics2D graphics, int width, boolean positionButtons) {
        int y = 10; messageBounds.clear();
        for (int index=0; index<entries.size(); index++) {
            Entry entry=entries.get(index);
            boolean continued=index>0 && sameGroup(entries.get(index-1),entry);
            boolean continues=index+1<entries.size() && sameGroup(entry,entries.get(index+1));
            if (entry.notice) {
                messageBounds.add(new Rectangle());
                Lines lines = wrap(entry.text, Math.max(80,width-100), SMALL);
                int w = (int)Math.ceil(lines.width)+24, h = (int)Math.ceil(lines.height)+8;
                if (graphics != null) {
                    int x = (width-w)/2;
                    graphics.setColor(new Color(0xD3E1E5)); graphics.fillRoundRect(x,y,w,h,22,22);
                    graphics.setColor(new Color(0x63777D)); drawLines(graphics,lines,x+12,y+4);
                }
                y += h+16;
                continue;
            }
            boolean sticker=entry.emoticonId != null;
            Lines lines = sticker ? null : wrap(entry.text, Math.max(100,Math.min(420,width-180)),BODY);
            int bubbleWidth = sticker ? 112 : (int)Math.ceil(lines.width)+28;
            int bubbleHeight = sticker ? 112 : (int)Math.ceil(lines.height)+16;
            if (entry.quote != null) {
                FontMetrics metrics = getFontMetrics(SMALL);
                int preview = Math.max(metrics.stringWidth(entry.quote.sender + "님에게 답장"), metrics.stringWidth(entry.quote.text));
                bubbleWidth = Math.max(bubbleWidth, Math.min(Math.max(100, Math.min(420, width-180)), preview) + 34);
                bubbleHeight += QUOTE_HEIGHT;
            }
            int x = entry.mine ? width-26-bubbleWidth : 72;
            int top = y+(entry.mine || continued ? 0 : 21);
            Rectangle bubble = new Rectangle(x, top, bubbleWidth, bubbleHeight);
            messageBounds.add(bubble);
            JButton replyButton = replyButtons.get(entry.messageId);
            if (positionButtons && replyButton != null) {
                int buttonX = entry.mine ? x - REPLY_GAP - REPLY_WIDTH : x + bubbleWidth + REPLY_GAP;
                int buttonY = top + (bubbleHeight - REPLY_HEIGHT) / 2;
                replyButton.setBounds(buttonX, buttonY, REPLY_WIDTH, REPLY_HEIGHT);
                replyButton.putClientProperty("sourceBounds", bubble);
            }
            if (graphics != null) {
                if (!entry.mine && !continued) {
                    Theme.avatar(entry.sender,36).paintIcon(this,graphics,22,y);
                    graphics.setFont(SMALL); graphics.setColor(new Color(0x60747A));
                    graphics.drawString(entry.sender,72,y+11);
                }
                if (sticker) {
                    new EmoticonIcon(entry.emoticonId,112).paintIcon(this,graphics,x,top);
                } else {
                graphics.setColor(entry.mine ? Theme.YELLOW : Color.WHITE);
                graphics.fillRoundRect(x,top,bubbleWidth,bubbleHeight,20,20);
                Path2D tail = new Path2D.Float();
                if (entry.mine) { tail.moveTo(x+bubbleWidth-7,top+7); tail.lineTo(x+bubbleWidth+5,top+5); tail.lineTo(x+bubbleWidth-2,top+18); }
                else { tail.moveTo(x+7,top+7); tail.lineTo(x-5,top+5); tail.lineTo(x+2,top+18); }
                tail.closePath(); if (!continued) graphics.fill(tail);
                int bodyTop = top + 8;
                if (entry.quote != null) {
                    graphics.setFont(SMALL);
                    graphics.setColor(Theme.MUTED);
                    graphics.fillRoundRect(x+12, top+9, 3, 29, 3, 3);
                    FontMetrics metrics = graphics.getFontMetrics();
                    graphics.drawString(fit(entry.quote.sender + "님에게 답장", bubbleWidth-32, metrics), x+20, top+19);
                    graphics.drawString(fit(entry.quote.text, bubbleWidth-32, metrics), x+20, top+35);
                    bodyTop += QUOTE_HEIGHT;
                }
                graphics.setColor(Theme.INK); drawLines(graphics,lines,x+14,bodyTop);
                if (entry.messageId != null && entry.messageId.equals(highlightedId)) {
                    graphics.setColor(new Color(0xA56A0D));
                    graphics.drawRoundRect(x, top, bubbleWidth-1, bubbleHeight-1, 20, 20);
                }
                }
                graphics.setFont(SMALL); graphics.setColor(new Color(0x73868C));
                int timeX = entry.mine ? x-38 : x+bubbleWidth+8;
                if (!continues && (replyButton == null || !replyButton.isVisible())) graphics.drawString(entry.time,timeX,top+bubbleHeight-3);
            }
            y = top + bubbleHeight + (continues ? 6 : 20);
        }
        return y+18;
    }
    private static boolean sameGroup(Entry left, Entry right) {
        return !left.notice && !right.notice && left.mine==right.mine &&
                right.arrivedAt>=left.arrivedAt && right.arrivedAt-left.arrivedAt<60_000 &&
                left.sender.equals(right.sender) && left.time.equals(right.time);
    }
    private static void drawLines(Graphics2D graphics, Lines lines, float x, float y) {
        for (TextLayout line : lines.layouts) {
            y += line.getAscent(); line.draw(graphics,x,y);
            y += line.getDescent()+line.getLeading()+5;
        }
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(getBackground()); g.fillRect(0,0,getWidth(),getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        render(g,getWidth()); g.dispose();
    }
    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() {
            @Override public String getAccessibleName() { return "채팅 대화"; }
            @Override public String getAccessibleDescription() { return getText(); }
        };
        return accessibleContext;
    }
    @Override public Dimension getPreferredSize() { return new Dimension(availableWidth(),render(null,availableWidth())); }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(680,440); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24,visible.height-24); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return getParent()!=null && getPreferredSize().height<getParent().getHeight(); }
}
