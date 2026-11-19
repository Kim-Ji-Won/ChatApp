package chatapp.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.time.LocalDate;

/** 날짜 선택과 해제를 한 칩으로 제공하는 통합 목록 필터. */
public final class PlannerDateFilter extends JPanel {
    private String value="";
    private final JButton select=new JButton(), clear=new JButton("×");
    private final JPopupMenu popup=new JPopupMenu();
    private final Runnable changed;
    public PlannerDateFilter(Runnable changed) {
        super(new BorderLayout()); this.changed=changed; setName("plannerFilter"); setOpaque(false);
        select.setName("plannerFilterButton"); clear.setName("plannerFilterClear");
        for(JButton button:new JButton[]{select,clear}) {
            button.setContentAreaFilled(false); button.setBorderPainted(false); button.setForeground(Theme.INK);
            button.setFont(new Font("SansSerif",Font.PLAIN,13)); button.setBorder(new EmptyBorder(9,12,9,12));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        clear.setFont(new Font("SansSerif",Font.PLAIN,18)); clear.setBorder(new EmptyBorder(4,7,4,10));
        clear.setToolTipText("날짜 필터 해제"); clear.getAccessibleContext().setAccessibleName("모든 날짜 보기");
        select.getAccessibleContext().setAccessibleName("계획 날짜 필터 선택");
        add(select,BorderLayout.CENTER); add(clear,BorderLayout.EAST);
        popup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        select.addActionListener(e->{
            popup.removeAll(); popup.add(PlannerPickers.calendar(value,date->{popup.setVisible(false); setValue(date);}));
            if(isShowing())popup.show(this,0,getHeight()+5);
        });
        clear.addActionListener(e->{popup.setVisible(false); setValue("");});
        addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0 && !isShowing())popup.setVisible(false);});
        refreshLabel();
    }
    public String getValue() { return value; }
    public void setValue(String next) {
        if(!next.isEmpty()) {
            if(!next.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("날짜 형식");
            LocalDate.parse(next);
        }
        value=next; refreshLabel(); changed.run();
    }
    public void refreshLabel() {
        select.setText(value.isEmpty()?"모든 날짜 ▾":PlannerDates.absolute(value,PlannerDates.now().toLocalDate()));
        select.setToolTipText(value.isEmpty()?"달력에서 날짜를 골라 보세요.":value+" · 다른 날짜 선택");
        clear.setVisible(!value.isEmpty()); revalidate(); repaint();
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(value.isEmpty()?new Color(0xF0EEE9):new Color(0xFFF4C2)); g.fillRoundRect(0,0,getWidth(),getHeight(),18,18); g.dispose();
        super.paintComponent(graphics);
    }
}
