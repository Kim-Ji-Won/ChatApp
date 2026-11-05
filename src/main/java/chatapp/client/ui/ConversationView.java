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
import java.util.function.Consumer;

/** 대화 내용을 평문으로 보관하고 가변 너비 말풍선으로 표시한다. 시각은 이 클라이언트 수신 시각. */
public final class ConversationView extends JComponent implements Scrollable {
    private record Entry(String sender, String text, boolean mine, boolean notice, String time, long arrivedAt) { }
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

    public ConversationView() {
        setName("transcript"); setOpaque(true); setBackground(Theme.CHAT);
        setToolTipText("메시지 옆 시간은 이 컴퓨터에서 받은 시각입니다.");
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("대화 내용 복사");
        copy.addActionListener(event -> getToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(getText()), null));
        menu.add(copy); setComponentPopupMenu(menu);
    }
    public void addMessage(String sender, String text, boolean mine) {
        append(new Entry(sender, text, mine, false, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), System.currentTimeMillis()));
    }
    public void addNotice(String text) { append(new Entry("", text, false, true, "", System.currentTimeMillis())); }
    public void clear() {
        revision++; scrollPending=false; entries.clear(); setUnseen(false);
        if (scrollPane!=null) scrollPane.getViewport().setViewPosition(new Point());
        revalidate(); repaint();
    }
    public void attachScrollPane(JScrollPane pane, Consumer<Boolean> listener) {
        scrollPane=pane; unseenListener=listener;
        pane.getViewport().addChangeListener(event -> {
            if (!scrollPending && nearBottom()) setUnseen(false);
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
        for (Entry e : entries) text.append(e.notice ? "[안내]" : e.sender).append('\n').append(e.text).append("\n\n");
        return text.toString();
    }
    private void append(Entry entry) {
        // 변경 전 위치를 잡고 같은 EDT 배치의 메시지들은 한 번에 스크롤 처리한다.
        scheduleScroll();
        entries.add(entry);
        if (entries.size()>300) {
            int before=getPreferredSize().height;
            while (entries.size()>300) entries.remove(0);
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
        return new Lines(layouts, width, height);
    }
    private int render(Graphics2D graphics, int width) {
        int y = 22;
        for (int index=0; index<entries.size(); index++) {
            Entry entry=entries.get(index);
            boolean continued=index>0 && sameGroup(entries.get(index-1),entry);
            boolean continues=index+1<entries.size() && sameGroup(entry,entries.get(index+1));
            if (entry.notice) {
                Lines lines = wrap(entry.text, Math.max(80,width-100), SMALL);
                int w = (int)Math.ceil(lines.width)+28, h = (int)Math.ceil(lines.height)+12;
                if (graphics != null) {
                    int x = (width-w)/2;
                    graphics.setColor(new Color(0xC9D9DD)); graphics.fillRoundRect(x,y,w,h,22,22);
                    graphics.setColor(new Color(0x63777D)); drawLines(graphics,lines,x+14,y+5);
                }
                y += h+24;
                continue;
            }
            Lines lines = wrap(entry.text, Math.max(100,Math.min(420,width-180)),BODY);
            int bubbleWidth = (int)Math.ceil(lines.width)+28;
            int bubbleHeight = (int)Math.ceil(lines.height)+18;
            int x = entry.mine ? width-26-bubbleWidth : 72;
            int top = y+(entry.mine || continued ? 0 : 21);
            if (graphics != null) {
                if (!entry.mine && !continued) {
                    Theme.avatar(entry.sender,36).paintIcon(this,graphics,22,y);
                    graphics.setFont(SMALL); graphics.setColor(new Color(0x60747A));
                    graphics.drawString(entry.sender,72,y+11);
                }
                graphics.setColor(entry.mine ? Theme.YELLOW : Color.WHITE);
                graphics.fillRoundRect(x,top,bubbleWidth,bubbleHeight,20,20);
                Path2D tail = new Path2D.Float();
                if (entry.mine) { tail.moveTo(x+bubbleWidth-7,top+7); tail.lineTo(x+bubbleWidth+5,top+5); tail.lineTo(x+bubbleWidth-2,top+18); }
                else { tail.moveTo(x+7,top+7); tail.lineTo(x-5,top+5); tail.lineTo(x+2,top+18); }
                tail.closePath(); if (!continued) graphics.fill(tail);
                graphics.setColor(Theme.INK); drawLines(graphics,lines,x+14,top+8);
                graphics.setFont(SMALL); graphics.setColor(new Color(0x73868C));
                int timeX = entry.mine ? x-38 : x+bubbleWidth+8;
                if (!continues) graphics.drawString(entry.time,timeX,top+bubbleHeight-3);
            }
            y = top+bubbleHeight+(continues ? 6 : 20);
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
    @Override public Dimension getPreferredSize() { return new Dimension(availableWidth(),render(null,availableWidth())); }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(680,440); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24,visible.height-24); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return getParent()!=null && getPreferredSize().height<getParent().getHeight(); }
}
