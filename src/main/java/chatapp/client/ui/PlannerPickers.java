package chatapp.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Function;

/** 날짜와 시간을 선택하고 통신 규칙의 문자열 형식으로 돌려주는 입력 도구. */
public final class PlannerPickers {
    private static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    private PlannerPickers() { }

    public static JPanel dateInput(JTextField field,Runnable changed) {
        field.putClientProperty("placeholder","날짜 선택");
        return input(field,"달력",value -> calendar(field.getText(),value),changed);
    }
    public static JPanel timeInput(JTextField field) {
        field.putClientProperty("placeholder","시간 선택");
        return input(field,"시간",value -> clock(field.getText(),value),()->{});
    }
    private static JPanel input(JTextField field,String label,Function<Consumer<String>,JPanel> factory,Runnable changed) {
        field.setEditable(false); field.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JButton trigger=smallButton(field.getName()+"Picker",label);
        trigger.getAccessibleContext().setAccessibleName(label+" 선택 열기");
        JPanel wrapper=Theme.panel(new BorderLayout(5,0)); wrapper.add(field,BorderLayout.CENTER); wrapper.add(trigger,BorderLayout.EAST);
        JPopupMenu popup=new JPopupMenu(); popup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        Runnable open=()->{
            if(!field.isEnabled() || !wrapper.isShowing()) return;
            popup.removeAll(); popup.add(factory.apply(value->{
                popup.setVisible(false);
                if(field.isEnabled()) { field.setText(value); changed.run(); }
            }));
            popup.show(wrapper,0,wrapper.getHeight()+5);
        };
        trigger.addActionListener(e->open.run());
        field.addMouseListener(new MouseAdapter(){ public void mouseClicked(MouseEvent e){ if(SwingUtilities.isLeftMouseButton(e)) open.run(); }});
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"openPicker");
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,0),"openPicker");
        field.getActionMap().put("openPicker",new AbstractAction(){ public void actionPerformed(ActionEvent e){open.run();} });
        field.addPropertyChangeListener("enabled",e->{trigger.setEnabled(field.isEnabled()); if(!field.isEnabled())popup.setVisible(false);});
        field.addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0 && !field.isShowing())popup.setVisible(false);});
        return wrapper;
    }

    public static JPanel calendar(String initial,Consumer<String> chosen) {
        return new CalendarPanel(initial,chosen);
    }
    private static final class CalendarPanel extends JPanel {
        private YearMonth month;
        private LocalDate selected;
        private final Consumer<String> chosen;
        private final JLabel caption=Theme.label("",16,true);
        private final JPanel days=Theme.panel(new GridLayout(6,7,3,3));
        private final JButton previous=smallButton("calendarPrevious","‹"), next=smallButton("calendarNext","›");
        CalendarPanel(String initial,Consumer<String> chosen) {
            super(new BorderLayout(0,14)); this.chosen=chosen;
            try { selected=LocalDate.parse(initial); } catch(DateTimeException ignored) { }
            month=YearMonth.from(selected==null?LocalDate.now(KOREA):selected);
            setName("calendarPicker"); setBackground(Theme.BACKGROUND); setBorder(new EmptyBorder(16,16,14,16));
            setPreferredSize(new Dimension(336,362));
            JPanel header=Theme.panel(new BorderLayout()); caption.setName("calendarMonth"); caption.setHorizontalAlignment(SwingConstants.CENTER);
            previous.getAccessibleContext().setAccessibleName("이전 달"); next.getAccessibleContext().setAccessibleName("다음 달");
            header.add(previous,BorderLayout.WEST); header.add(caption,BorderLayout.CENTER); header.add(next,BorderLayout.EAST); add(header,BorderLayout.NORTH);
            JPanel body=Theme.panel(new BorderLayout(0,8)), weekdays=Theme.panel(new GridLayout(1,7));
            for(String day:new String[]{"월","화","수","목","금","토","일"}) {
                JLabel text=Theme.label(day,12,false); text.setHorizontalAlignment(SwingConstants.CENTER); text.setForeground(Theme.MUTED); weekdays.add(text);
            }
            body.add(weekdays,BorderLayout.NORTH); body.add(days,BorderLayout.CENTER); add(body,BorderLayout.CENTER);
            JPanel footer=Theme.panel(new BorderLayout());
            JButton today=smallButton("calendarToday","오늘"), clear=smallButton("calendarClear","날짜 지우기");
            today.addActionListener(e->chosen.accept(LocalDate.now(KOREA).toString())); clear.addActionListener(e->chosen.accept(""));
            footer.add(today,BorderLayout.WEST); footer.add(clear,BorderLayout.EAST); add(footer,BorderLayout.SOUTH);
            previous.addActionListener(e->{month=month.minusMonths(1); rebuild();}); next.addActionListener(e->{month=month.plusMonths(1); rebuild();});
            rebuild();
        }
        private void rebuild() {
            caption.setText(month.getYear()+"년 "+month.getMonthValue()+"월"); days.removeAll();
            previous.setEnabled(month.getYear()>1 || month.getMonthValue()>1);
            next.setEnabled(month.getYear()<9999 || month.getMonthValue()<12);
            int offset=month.atDay(1).getDayOfWeek().getValue()-1;
            for(int cell=0;cell<42;cell++) {
                int day=cell-offset+1;
                if(day<1 || day>month.lengthOfMonth()) { days.add(new JLabel()); continue; }
                LocalDate value=month.atDay(day); boolean active=value.equals(selected);
                JButton button=Theme.button(Integer.toString(day),active); button.setName("calendarDay"+day);
                button.setBorder(new EmptyBorder(3,3,3,3)); button.getAccessibleContext().setAccessibleName(value.toString());
                button.addActionListener(e->chosen.accept(value.toString())); days.add(button);
            }
            revalidate(); repaint();
        }
    }

    public static JPanel clock(String initial,Consumer<String> chosen) {
        JPanel panel=new JPanel(new BorderLayout(0,18)); panel.setName("timePicker"); panel.setBackground(Theme.BACKGROUND);
        panel.setBorder(new EmptyBorder(18,18,16,18)); panel.setPreferredSize(new Dimension(280,216));
        panel.add(Theme.label("시간 선택 · 24시간",16,true),BorderLayout.NORTH);
        LocalTime value=LocalTime.of(12,0);
        try { value=LocalTime.parse(initial); } catch(DateTimeException ignored) { }
        // JPopupMenu 내부에서 JComboBox가 또 팝업을 열면 macOS에서 바깥 팝업이 취소될 수 있다.
        // 시·분은 별도 팝업을 띄우지 않는 숫자 조절기로 선택한다.
        JSpinner hours=numberSpinner("timeHours",value.getHour(),23,"시"), minutes=numberSpinner("timeMinutes",value.getMinute(),59,"분");
        JPanel selectors=Theme.panel(new GridLayout(1,2,12,0));
        selectors.add(labeled("시",hours)); selectors.add(labeled("분",minutes)); panel.add(selectors,BorderLayout.CENTER);
        JButton clear=smallButton("timeClear","시간 미정"), apply=Theme.button("선택",true); apply.setName("timeApply");
        JLabel error=Theme.label("시는 0~23, 분은 0~59로 입력해 주세요.",11,false); error.setName("timeError"); error.setForeground(new Color(0xA33F2D)); error.setVisible(false);
        apply.addActionListener(e->{
            try {
                hours.commitEdit(); minutes.commitEdit();
                int hour=((Number)hours.getValue()).intValue(), minute=((Number)minutes.getValue()).intValue();
                if(hour<0 || hour>23 || minute<0 || minute>59)throw new java.text.ParseException("범위",0);
                error.setVisible(false);
                chosen.accept(LocalTime.of(hour,minute).format(DateTimeFormatter.ofPattern("HH:mm",java.util.Locale.ROOT)));
            } catch(java.text.ParseException invalid) {
                error.setVisible(true); panel.revalidate(); panel.repaint();
            }
        });
        clear.addActionListener(e->chosen.accept(""));
        JPanel footer=Theme.panel(new BorderLayout(0,8)); JPanel actions=Theme.panel(new BorderLayout());
        actions.add(clear,BorderLayout.WEST); actions.add(apply,BorderLayout.EAST); footer.add(error,BorderLayout.NORTH); footer.add(actions,BorderLayout.CENTER); panel.add(footer,BorderLayout.SOUTH);
        return panel;
    }
    private static JSpinner numberSpinner(String name,int value,int maximum,String accessibleName) {
        JSpinner spinner=new JSpinner(new SpinnerNumberModel(value,0,maximum,1)); spinner.setName(name);
        JSpinner.NumberEditor editor=new JSpinner.NumberEditor(spinner,"00"); spinner.setEditor(editor);
        JFormattedTextField input=editor.getTextField(); input.setFont(new Font("SansSerif",Font.BOLD,20)); input.setHorizontalAlignment(SwingConstants.CENTER);
        input.setBorder(new EmptyBorder(6,6,6,6)); input.setFocusLostBehavior(JFormattedTextField.PERSIST);
        spinner.getAccessibleContext().setAccessibleName(accessibleName); input.getAccessibleContext().setAccessibleName(accessibleName+" 직접 입력");
        spinner.setToolTipText("화살표로 조절하거나 숫자를 직접 입력하세요."); return spinner;
    }
    private static JPanel labeled(String caption,JComponent component) {
        JPanel panel=Theme.panel(new BorderLayout(0,5)); panel.add(Theme.label(caption,12,false),BorderLayout.NORTH); panel.add(component); return panel;
    }
    private static JButton smallButton(String name,String label) {
        JButton button=Theme.button(label,false); button.setName(name); button.setBorder(new EmptyBorder(7,10,7,10)); return button;
    }
}
