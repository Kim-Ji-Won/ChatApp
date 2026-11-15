package chatapp.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** EDT 타이머는 화면 연출만 담당한다. 요청이나 승패 판정은 수행하지 않는다. */
final class GameStage extends JPanel {
    private final CardLayout pages = new CardLayout();
    private final JPanel results = Theme.panel(new GridLayout(0, 3, 6, 6));
    private final Canvas canvas = new Canvas();
    private final javax.swing.Timer timer = new javax.swing.Timer(33, e -> tick());
    private String phase = "idle";
    private long started;
    private boolean revealed;
    private String selected;
    private Runnable onReveal = () -> { };

    GameStage() {
        super(); setLayout(pages); setOpaque(false); setName("gameStage");
        add(canvas, "animation");
        JScrollPane scroll = new JScrollPane(results); scroll.setBorder(null);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24); add(scroll, "results");
        setPreferredSize(new Dimension(220, 104)); setMinimumSize(new Dimension(0, 104));
        putClientProperty("phase", phase);
    }
    void idle() { timer.stop(); selected=null; phase="idle"; showCanvas(); }
    void waiting() { timer.stop(); selected=null; phase="waiting"; started=System.nanoTime(); showCanvas(); resume(); }
    void selected(String value) { selected=value; repaint(); }
    void reveal(List<String> names, List<String> moves, List<String> winners, Runnable done) {
        timer.stop(); results.removeAll();
        for (int i=0; i<names.size(); i++) {
            String name=names.get(i), move=moves.get(i); boolean winner=winners.contains(name);
            JPanel card=Theme.rounded(new BorderLayout(4,0), winner ? new Color(0xFFEBA5) : Color.WHITE,16);
            card.setBorder(new EmptyBorder(5,7,5,7));
            JLabel hand=new JLabel(new GameHandIcon(move,40)); card.add(hand,BorderLayout.WEST);
            JPanel words=Theme.panel(new GridLayout(2,1));
            JLabel person=Theme.label(name,12,true); person.setToolTipText(name);
            words.add(person); words.add(Theme.label(RoomGameView.choiceLabel(move)+(winner ? " · 승리" : winners.isEmpty() ? " · 무승부" : ""),11,false));
            card.add(words,BorderLayout.CENTER); results.add(card);
        }
        onReveal=done; revealed=false; phase="reveal"; started=System.nanoTime(); showCanvas(); resume();
    }
    private void showCanvas() { pages.show(this,"animation"); putClientProperty("phase",phase); repaint(); }
    private void tick() {
        if (phase.equals("reveal") && elapsed() >= 1050) finishReveal();
        else repaint();
    }
    private long elapsed() { return (System.nanoTime()-started)/1_000_000; }
    private void finishReveal() {
        timer.stop(); phase="result"; putClientProperty("phase",phase); pages.show(this,"results");
        if (!revealed) { revealed=true; onReveal.run(); }
        revalidate(); repaint();
    }
    void pause() { timer.stop(); if (phase.equals("reveal")) finishReveal(); }
    void resume() { if (phase.equals("waiting") || phase.equals("reveal")) timer.start(); }
    @Override public void removeNotify() { timer.stop(); super.removeNotify(); }
    @Override public void addNotify() { super.addNotify(); if (isShowing()) resume(); }
    private final class Canvas extends JPanel {
        Canvas() { setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int cx=getWidth()/2;
            if (phase.equals("idle")) {
                for(int i=0;i<3;i++) new GameHandIcon(List.of("SCISSORS","ROCK","PAPER").get(i),62).paintIcon(this,g,cx-100+i*68,0);
                caption(g,"같이 골라요, 결과는 함께!",88);
            } else {
                String move="ROCK", text=selected==null ? "어떤 손을 낼까요?" : "내 선택 · "+RoomGameView.choiceLabel(selected)+"  /  친구들을 기다려요";
                int dy=(int)(Math.sin(elapsed()/160.0)*5);
                if(phase.equals("reveal")) {
                    int beat=(int)Math.min(2,elapsed()/350);
                    move=List.of("SCISSORS","ROCK","PAPER").get(beat);
                    text=List.of("가위…","바위…","보!").get(beat);
                }
                new GameHandIcon(move,72).paintIcon(this,g,cx-36,dy);
                caption(g,text,94);
            }
            g.dispose();
        }
        private void caption(Graphics2D g,String text,int y) {
            g.setColor(Theme.INK); g.setFont(new Font("SansSerif",Font.BOLD,12));
            g.drawString(text,(getWidth()-g.getFontMetrics().stringWidth(text))/2,y);
        }
    }
}
