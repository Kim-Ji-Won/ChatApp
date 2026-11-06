package chatapp.client.ui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

public final class Theme {
    public static final Color BACKGROUND = new Color(0xFAF9F6);
    public static final Color INK = new Color(0x302D29);
    public static final Color MUTED = new Color(0x8B8780);
    public static final Color YELLOW = new Color(0xFFE36D);
    public static final Color CHAT = new Color(0xDDE9EC);
    public static final Color BORDER = new Color(0xECE9E2);
    private Theme() { }
    public static void install() {
        Font font = new Font("SansSerif", Font.PLAIN, 14);
        for (Object key : UIManager.getDefaults().keySet().toArray())
            if (UIManager.get(key) instanceof Font) UIManager.put(key, font);
        UIManager.put("Label.foreground", INK);
        UIManager.put("TextField.foreground", INK);
        UIManager.put("List.selectionBackground", new Color(0xFFF6CE));
        UIManager.put("List.selectionForeground", INK);
    }
    public static JLabel label(String text, int size, boolean bold) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(INK);
        return label;
    }
    public static JButton button(String text, boolean primary) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = primary ? YELLOW : new Color(0xF0EEE9);
                if (!isEnabled()) fill = new Color(0xF3F1EC);
                else if (getModel().isPressed()) fill = fill.darker();
                else if (getModel().isRollover()) fill = primary ? new Color(0xFFDB4D) : new Color(0xE7E4DD);
                g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setFont(new Font("SansSerif", Font.BOLD, 13));
        button.setFocusPainted(true);
        button.setContentAreaFilled(false); button.setOpaque(false); button.setBorderPainted(false);
        button.setForeground(INK);
        button.setBorder(new EmptyBorder(11, 18, 11, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
    public static JTextField field(String name, String initial) {
        JTextField field = new JTextField(initial) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.WHITE); g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g.setColor(hasFocus() ? new Color(0xE0C45D) : BORDER);
                g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 18, 18);
                g.dispose(); super.paintComponent(graphics);
                if (getText().isEmpty() && getClientProperty("placeholder") instanceof String hint) {
                    g = (Graphics2D) graphics.create();
                    g.setColor(MUTED); g.setFont(getFont());
                    g.drawString(hint, getInsets().left, (getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());
                    g.dispose();
                }
            }
        };
        field.setName(name); field.setOpaque(false);
        field.setBorder(new EmptyBorder(12, 15, 12, 15));
        return field;
    }
    public static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel;
    }
    public static JPanel rounded(LayoutManager layout, Color color, int radius) {
        JPanel panel = new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color); g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius); g.dispose();
                super.paintComponent(graphics);
            }
        };
        panel.setOpaque(false); return panel;
    }
    public static Icon avatar(String name, int size) {
        Color[] palette = {new Color(0xEBDDF1), new Color(0xD6E9DC), new Color(0xF5DDCE), new Color(0xD7E4EF)};
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(palette[Math.floorMod(name.hashCode(), palette.length)]);
                g.fillRoundRect(x, y, size, size, size/2, size/2);
                g.setFont(new Font("SansSerif", Font.BOLD, size/3)); g.setColor(INK);
                String initial = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0,1));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(initial, x+(size-fm.stringWidth(initial))/2, y+(size-fm.getHeight())/2+fm.getAscent());
                g.dispose();
            }
        };
    }
    public static Icon smile(int size) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(YELLOW); g.fillRoundRect(x, y, size, size, size/2, size/2);
                g.setColor(INK); int eye = Math.max(3,size/12);
                g.fillOval(x+size/3-eye/2,y+size/3,eye,eye); g.fillOval(x+size*2/3-eye/2,y+size/3,eye,eye);
                g.setStroke(new BasicStroke(Math.max(2,size/30f),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.drawArc(x+size/3,y+size/3,size/3,size/3,195,150); g.dispose();
            }
        };
    }
}
