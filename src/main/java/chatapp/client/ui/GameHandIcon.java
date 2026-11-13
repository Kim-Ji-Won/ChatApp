package chatapp.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;

/** 벡터로 그려 폰트와 운영체제에 관계없이 같은 손 모양을 표시한다. */
final class GameHandIcon implements Icon {
    private final String choice;
    private final int size;
    GameHandIcon(String choice, int size) { this.choice = choice; this.size = size; }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.translate(x, y); g.scale(size / 80.0, size / 80.0);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color fill = new Color(0xFFD786), outline = new Color(0x896438);
        // 손가락을 먼저 그리고 손바닥을 겹쳐 자연스럽게 연결한다.
        if (choice.equals("PAPER")) {
            finger(g, 23, 13, 11, 37, fill, outline);
            finger(g, 34, 6, 11, 43, fill, outline);
            finger(g, 45, 10, 11, 39, fill, outline);
            finger(g, 56, 20, 10, 33, fill, outline);
        } else if (choice.equals("SCISSORS")) {
            Graphics2D left = (Graphics2D) g.create(); left.rotate(-0.24, 34, 45);
            finger(left, 28, 5, 12, 45, fill, outline); left.dispose();
            Graphics2D right = (Graphics2D) g.create(); right.rotate(0.24, 45, 45);
            finger(right, 40, 5, 12, 45, fill, outline); right.dispose();
        }
        Shape palm = new RoundRectangle2D.Double(22, 35, 44, 37, 20, 20);
        g.setColor(fill); g.fill(palm); g.setColor(outline); g.draw(palm);
        if (choice.equals("ROCK")) {
            for (int i = 0; i < 4; i++) finger(g, 21 + i * 11, 25 + Math.abs(i-1)*2, 12, 24, fill, outline);
        } else if (choice.equals("SCISSORS")) {
            finger(g, 46, 33, 10, 22, fill, outline); finger(g, 56, 36, 10, 20, fill, outline);
        }
        Graphics2D thumb = (Graphics2D) g.create(); thumb.rotate(-0.55, 24, 52);
        finger(thumb, 14, 39, 14, 26, fill, outline); thumb.dispose();
        g.setColor(outline); g.draw(new QuadCurve2D.Double(33, 59, 40, 55, 47, 60));
        g.dispose();
    }
    private static void finger(Graphics2D g, double x, double y, double w, double h, Color fill, Color edge) {
        Shape shape = new RoundRectangle2D.Double(x,y,w,h,w,w);
        g.setColor(fill); g.fill(shape); g.setColor(edge); g.draw(shape);
    }
}
