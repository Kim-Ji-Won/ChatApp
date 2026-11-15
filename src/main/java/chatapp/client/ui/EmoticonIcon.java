package chatapp.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;

/** 단순한 선과 파스텔 소품으로 그린 공통 캐릭터. 파일·이모지 폰트 없이 확대해도 선명하다. */
final class EmoticonIcon implements Icon {
    private static final Color LINE = new Color(0x65554B);
    private static final Color CREAM = new Color(0xFFF0BA);
    private static final Color PINK = new Color(0xF2A6AE);
    private static final Color MINT = new Color(0xA9D8C3);
    private final String id;
    private final int size;
    private final boolean locked;
    EmoticonIcon(String id, int size) { this(id,size,false); }
    EmoticonIcon(String id, int size, boolean locked) { this.id=id; this.size=size; this.locked=locked; }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g=(Graphics2D)graphics.create();
        g.translate(x,y); g.scale(size/112.0,size/112.0);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(3.0f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        if(locked) g.setComposite(AlphaComposite.SrcOver.derive(.3f));
        // 그림자, 작은 발, 둥근 몸. 반듯한 원 대신 약간 비대칭인 윤곽을 쓴다.
        g.setColor(new Color(0x1865554B,true)); g.fill(new Ellipse2D.Double(28,95,58,7));
        shape(g,new Ellipse2D.Double(34,87,17,11),CREAM);
        shape(g,new Ellipse2D.Double(64,87,17,11),CREAM);
        Path2D body=new Path2D.Double(); body.moveTo(25,60);
        body.curveTo(24,36,35,24,55,26); body.curveTo(78,22,88,41,88,62);
        body.curveTo(94,83,79,94,57,93); body.curveTo(31,96,20,82,25,60);
        shape(g,body,CREAM);
        g.setColor(PINK); g.fill(new Ellipse2D.Double(29,59,14,7)); g.fill(new Ellipse2D.Double(72,59,13,7));
        if(id.equals("E07")) {
            shape(g,star(43,50,8),new Color(0xE9BE56)); shape(g,star(70,50,8),new Color(0xE9BE56));
        } else if(id.equals("E01") || id.equals("E04") || id.equals("E08")) {
            line(g,36,53,41,49,47,53); line(g,65,53,70,49,76,53);
        } else {
            g.setColor(LINE); g.fill(new Ellipse2D.Double(40,47,5,7)); g.fill(new Ellipse2D.Double(68,47,5,7));
        }
        g.setColor(LINE);
        if(id.equals("E03") || id.equals("E06") || id.equals("E07")) {
            shape(g,new RoundRectangle2D.Double(49,61,15,14,10,10),PINK);
        } else g.draw(new QuadCurve2D.Double(48,63,56,73,65,63));
        switch(id) {
            case "E01" -> { arm(g,29,70,17,67,16,57); arm(g,83,70,95,67,96,57); shape(g,heart(83,10,17),PINK); }
            case "E02" -> {
                arm(g,29,73,20,78,18,70);
                Path2D thumb=new Path2D.Double(); thumb.moveTo(80,75); thumb.lineTo(80,57); thumb.lineTo(87,50);
                thumb.lineTo(89,36); thumb.curveTo(98,33,99,47,96,52); thumb.lineTo(104,52);
                thumb.curveTo(109,52,105,70,101,75); thumb.closePath(); shape(g,thumb,CREAM);
                sparkle(g,99,23,5);
            }
            case "E03" -> {
                Path2D hat=new Path2D.Double(); hat.moveTo(37,30); hat.lineTo(56,5); hat.lineTo(72,30); hat.closePath();
                shape(g,hat,MINT); g.setColor(Color.WHITE); g.fillOval(52,15,5,5); g.fillOval(58,24,5,5);
                arm(g,27,68,13,55,12,44); arm(g,85,68,99,52,100,42);
                confetti(g);
            }
            case "E04" -> {
                shape(g,heart(37,73,39),PINK);
                shape(g,new Ellipse2D.Double(31,76,16,11),CREAM); shape(g,new Ellipse2D.Double(68,76,16,11),CREAM);
            }
            case "E05" -> {
                arm(g,26,69,12,61,15,42); arm(g,87,69,102,60,99,42);
                shape(g,new Ellipse2D.Double(8,34,14,15),CREAM); shape(g,new Ellipse2D.Double(93,34,14,15),CREAM);
                sparkle(g,23,17,6); sparkle(g,91,17,6);
            }
            case "E06" -> {
                arm(g,27,70,18,78,15,73); arm(g,85,70,97,58,95,40);
                shape(g,new Ellipse2D.Double(88,28,14,18),CREAM);
                g.setColor(LINE); g.draw(new Arc2D.Double(96,19,13,31,-65,130,Arc2D.OPEN));
                g.draw(new Line2D.Double(84,22,80,17));
            }
            case "E07" -> { arm(g,27,71,17,59,17,52); arm(g,86,71,98,59,97,52); sparkle(g,15,25,7); sparkle(g,96,18,6); }
            case "E08" -> {
                Graphics2D paws=(Graphics2D)g.create(); paws.rotate(-.3,56,79);
                shape(paws,new RoundRectangle2D.Double(40,68,17,24,15,15),CREAM);
                shape(paws,new RoundRectangle2D.Double(59,68,17,24,15,15),CREAM); paws.dispose();
                sparkle(g,92,37,7); g.setColor(LINE); g.drawLine(19,70,13,68); g.drawLine(20,79,12,81);
            }
            default -> { }
        }
        if(locked) {
            g.setComposite(AlphaComposite.SrcOver); g.setColor(new Color(0xF0EEE9)); g.fillOval(82,80,28,28);
            g.setColor(new Color(0x928A7F)); g.draw(new RoundRectangle2D.Double(90,85,12,13,9,9));
            g.fillRoundRect(87,93,18,13,4,4);
        }
        g.dispose();
    }
    private static void shape(Graphics2D g,Shape s,Color fill) { g.setColor(fill); g.fill(s); g.setColor(LINE); g.draw(s); }
    private static void arm(Graphics2D g,int x,int y,int cx,int cy,int endX,int endY) {
        g.setColor(LINE); g.draw(new QuadCurve2D.Double(x,y,cx,cy,endX,endY));
    }
    private static void line(Graphics2D g,int x,int y,int cx,int cy,int ex,int ey) {
        g.setColor(LINE); Path2D p=new Path2D.Double(); p.moveTo(x,y); p.quadTo(cx,cy,ex,ey); g.draw(p);
    }
    private static Shape heart(double x,double y,double w) {
        Path2D p=new Path2D.Double(); p.moveTo(x+w/2,y+w*.25);
        p.curveTo(x+w*.1,y-w*.1,x-w*.2,y+w*.4,x+w/2,y+w*.9);
        p.curveTo(x+w*1.2,y+w*.4,x+w*.9,y-w*.1,x+w/2,y+w*.25); p.closePath(); return p;
    }
    private static Shape star(double x,double y,double r) {
        Path2D p=new Path2D.Double();
        for(int i=0;i<10;i++) { double a=-Math.PI/2+i*Math.PI/5, n=i%2==0?r:r*.48;
            double px=x+Math.cos(a)*n,py=y+Math.sin(a)*n; if(i==0)p.moveTo(px,py);else p.lineTo(px,py); }
        p.closePath(); return p;
    }
    private static void sparkle(Graphics2D g,int x,int y,int r) { shape(g,star(x,y,r),new Color(0xF5CF77)); }
    private static void confetti(Graphics2D g) {
        Color[] colors={PINK,MINT,new Color(0xB6B1DE)};
        int[][] bits={{15,17,19,24},{90,12,85,19},{7,34,12,31},{101,29,106,34}};
        for(int i=0;i<bits.length;i++){g.setColor(colors[i%3]);int[] b=bits[i];g.drawLine(b[0],b[1],b[2],b[3]);}
    }
}
