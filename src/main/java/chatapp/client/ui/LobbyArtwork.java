package chatapp.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/** 채팅방의 노란 말풍선과 하늘색 배경을 이어주는 목록 화면의 빈 상태 그림. */
final class LobbyArtwork extends JComponent {
    LobbyArtwork() { setPreferredSize(new Dimension(260,190)); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g=(Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xD0E0E3)); g.fillOval(25,10,210,174);
        g.setColor(new Color(255,255,255,100)); g.fillOval(9,47,12,12); g.fillOval(234,125,8,8);
        bubble(g,35,32,145,87,Color.WHITE,false);
        g.setColor(new Color(0xA5B6BA));
        for(int i=0;i<3;i++)g.fillOval(76+i*21,69,9,9);
        bubble(g,103,91,126,72,Theme.YELLOW,true);
        g.setColor(Theme.INK);g.fillOval(142,114,5,6);g.fillOval(183,114,5,6);
        g.setStroke(new BasicStroke(3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g.drawArc(153,120,24,15,195,150);
        g.setColor(new Color(0xABC2C8)); g.setStroke(new BasicStroke(2f));
        g.drawLine(220,39,220,51);g.drawLine(214,45,226,45);
        g.dispose();
    }
    private static void bubble(Graphics2D g,int x,int y,int w,int h,Color color,boolean right) {
        g.setColor(color);g.fillRoundRect(x,y,w,h,28,28);
        Path2D tail=new Path2D.Float();
        if(right){tail.moveTo(x+w-24,y+h-3);tail.lineTo(x+w-5,y+h+10);tail.lineTo(x+w-8,y+h-15);}
        else {tail.moveTo(x+24,y+h-3);tail.lineTo(x+5,y+h+10);tail.lineTo(x+8,y+h-15);}
        tail.closePath();g.fill(tail);
    }
}
