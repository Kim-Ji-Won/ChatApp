package chatapp.client.ui;

import chatapp.client.planner.PlannerState;
import chatapp.client.planner.PlannerState.*;
import chatapp.common.Message;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/** 방별 할 일·일정 화면. 입력 폼은 서버 목록과 별도로 보관하여 오류와 충돌 때 유지한다. */
public final class PlannerPanel extends JPanel {
    private record Pending(Message request,String response,String entity) { }
    private final PlannerState state=new PlannerState();
    private final Function<Message,Boolean> sender;
    private final Runnable returnToChat;
    public record PlanKey(String kind,String id) { }
    private final Deque<String> queuedLists=new ArrayDeque<>();
    private final JButton addTodo=button("plannerAddTodo","할 일",false), addSchedule=button("plannerAddSchedule","약속",false);
    private final JPanel choicePage=Theme.panel(new BorderLayout(0,22));
    private final JButton refresh=quietButton("plannerRefresh","↻"), create=button("plannerNew","새로 만들기",true);
    private final JButton save=button("plannerSave","등록하기",true), delete=button("plannerDelete","삭제",false);
    private final JButton complete=button("plannerComplete","완료로 표시",false), review=button("plannerReview","최신 내용 확인",false);
    private final JButton discard=quietButton("plannerDiscard","입력 취소");
    private final JButton close=quietButton("plannerClose","×"), back=quietButton("plannerBack","← 목록");
    private final JButton resume=quietButton("plannerResume","작성 중인 내용 이어서 보기 →");
    private final JButton sourceToggle=quietButton("plannerSourceToggle","원문 보기 ▾");
    private final CardLayout contentCards=new CardLayout();
    private final JPanel content=Theme.panel(contentCards);
    private final JPanel listPage=Theme.panel(new BorderLayout(0,14));
    private final JPanel formPage=Theme.panel(new BorderLayout(0,12));
    private final CardLayout listCards=new CardLayout();
    private final JPanel listContent=Theme.panel(listCards);
    private final JLabel emptyTitle=Theme.label("아직 할 일이 없어요",20,true);
    private final JLabel emptyHint=Theme.label("대화에서 함께할 일을 골라 보세요.",13,false);
    private final JLabel timeCaption=Theme.label("마감 시간 · 선택",12,false);
    private final JLabel dateCaption=Theme.label("마감일 · 선택",12,false);
    private final JPanel sourceSection=Theme.panel(new BorderLayout(0,8));
    private boolean showingForm;
    private final PlannerDateFilter filter=new PlannerDateFilter(this::filterChanged);
    private final JTextField title=Theme.field("plannerTitle","");
    private final JTextField date=Theme.field("plannerDate","");
    private final JTextField time=Theme.field("plannerTime","");
    private final JComboBox<String> assignee=new JComboBox<>();
    private final JTextArea memo=area("plannerMemo",3), source=area("plannerSource",3), feedback=area("plannerFeedback",3);
    private final JScrollPane feedbackScroll=new JScrollPane(feedback) {
        @Override public Dimension getPreferredSize() {
            Insets outer=PlannerPanel.this.getInsets(), text=feedback.getInsets();
            int width=Math.max(120,PlannerPanel.this.getWidth()-outer.left-outer.right-24);
            javax.swing.text.View view=feedback.getUI().getRootView(feedback);
            view.setSize(Math.max(80,width-text.left-text.right),Float.MAX_VALUE);
            int height=(int)Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))+text.top+text.bottom+6;
            return new Dimension(100,Math.max(44,Math.min(140,height)));
        }
    };
    private boolean spaceHeld;
    private final JTextArea latest=area("plannerLatest",4);
    private final JLabel formTitle=Theme.label("대화에서 할 일을 가져와 보세요",17,true);
    private final JLabel count=Theme.label("아직 조회하지 않았어요",12,false);
    private final DefaultListModel<PlanKey> rows=new DefaultListModel<>();
    private final JList<PlanKey> list=new JList<>(rows) {
        private PlanKey pressedQuick;
        private boolean quickGesture, lastWasQuick;
        @Override protected void processMouseEvent(java.awt.event.MouseEvent event) {
            if(SwingUtilities.isLeftMouseButton(event)) {
                if(event.getID()==java.awt.event.MouseEvent.MOUSE_PRESSED) {
                    pressedQuick=quickKeyAt(event.getPoint());
                    quickGesture=pressedQuick!=null || (event.getClickCount()>1 && lastWasQuick); lastWasQuick=quickGesture;
                }
                if(quickGesture) {
                    if(event.getID()==java.awt.event.MouseEvent.MOUSE_RELEASED && event.getClickCount()==1
                            && pressedQuick!=null && pressedQuick.equals(quickKeyAt(event.getPoint()))) {
                        requestFocusInWindow(); toggleFromList(pressedQuick);
                    }
                    event.consume(); return;
                }
            }
            super.processMouseEvent(event);
        }
        @Override protected void processMouseMotionEvent(java.awt.event.MouseEvent event) {
            if(quickGesture && event.getID()==java.awt.event.MouseEvent.MOUSE_DRAGGED) {event.consume(); return;}
            PlanKey key=quickKeyAt(event.getPoint());
            setCursor(Cursor.getPredefinedCursor(key!=null && canQuickComplete(state.todo(key.id()))?Cursor.HAND_CURSOR:Cursor.DEFAULT_CURSOR));
            super.processMouseMotionEvent(event);
        }
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            PlanKey key=quickKeyAt(event.getPoint());
            if(key!=null) {
                Todo todo=state.todo(key.id());
                if(!mayComplete(todo)) return "작성자 또는 담당자만 완료 상태를 바꿀 수 있어요.";
                if(dirty || conflict) return "작성 중인 내용을 먼저 마무리해 주세요.";
                if(quickNeedsRefresh)return "목록을 새로고침한 뒤 다시 시도해 주세요.";
                if(!canQuickComplete(todo)) return "처리 중이에요. 잠시 기다려 주세요.";
                return todo.done()?"완료 취소":"완료로 표시";
            }
            int index=locationToIndex(event.getPoint());
            if(index<0 || !getCellBounds(index,index).contains(event.getPoint()))return null;
            PlanKey row=rows.get(index); return rowDate(row)+" "+rowTime(row);
        }
    };
    private final javax.swing.Timer dateRefresh=new javax.swing.Timer(30_000,e->{filter.refreshLabel(); list.repaint();});
    private String quickCompletionId;
    private boolean quickNeedsRefresh;
    private final JPanel assigneeRow, memoRow, sourceRow, filterRow, latestRow;
    private String kind="TODO", self, editId, sourceSender="", sourceText="";
    private long editRevision;
    private boolean ready, updating, dirty, conflict, removed, deleteArmed, listFresh;
    private Pending pending;
    private List<String> members=List.of();

    public PlannerPanel(Function<Message,Boolean> sender,Runnable returnToChat) {
        super(new BorderLayout(0,20)); this.sender=sender; this.returnToChat=returnToChat;
        setName("plannerPanel"); setBackground(Theme.BACKGROUND); setBorder(new EmptyBorder(20,26,18,26));
        JPanel top=Theme.panel(new BorderLayout());
        JPanel heading=Theme.panel(new GridLayout(2,1,0,6));
        JLabel headingTitle=Theme.label("우리의 계획",22,true); headingTitle.setName("plannerHeading");
        JLabel headingHint=Theme.label("함께 할 일과 약속을 한곳에",12,false); headingHint.setForeground(Theme.MUTED);
        heading.add(headingTitle); heading.add(headingHint); top.add(heading,BorderLayout.WEST);
        close.setText(""); close.setMargin(new Insets(0,0,0,0)); close.setBorder(new EmptyBorder(8,8,8,8));
        close.setPreferredSize(new Dimension(38,38));
        close.setIcon(new Icon() {
            public int getIconWidth(){ return 18; }
            public int getIconHeight(){ return 18; }
            public void paintIcon(Component c,Graphics graphics,int x,int y){
                Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.MUTED); g.setStroke(new BasicStroke(1.8f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.drawLine(x+4,y+4,x+14,y+14); g.drawLine(x+14,y+4,x+4,y+14); g.dispose();
            }
        });
        close.setToolTipText("닫고 대화로 돌아가기 · Esc"); close.getAccessibleContext().setAccessibleName("우리의 계획 닫기");
        top.add(close,BorderLayout.EAST); add(top,BorderLayout.NORTH);
        listPage.setName("plannerListPage"); formPage.setName("plannerFormPage");
        JPanel listHeader=Theme.panel(new BorderLayout(8,12));
        count.setForeground(Theme.MUTED); count.setToolTipText("이 방의 기록은 서버가 다시 시작되면 초기화돼요."); listHeader.add(count,BorderLayout.WEST);
        JPanel listActions=Theme.panel(new FlowLayout(FlowLayout.RIGHT,6,0));
        refresh.setFont(new Font("SansSerif",Font.PLAIN,22)); refresh.setToolTipText("새로고침");
        refresh.getAccessibleContext().setAccessibleName("목록 새로고침"); listActions.add(refresh); listActions.add(create); listHeader.add(listActions,BorderLayout.EAST);
        filterRow=Theme.panel(new FlowLayout(FlowLayout.LEFT,0,0)); filterRow.add(filter);
        listHeader.add(filterRow,BorderLayout.SOUTH); listPage.add(listHeader,BorderLayout.NORTH);
        list.setName("plannerList"); list.setToolTipText("계획을 선택해 자세히 보기"); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setFixedCellHeight(94);
        list.setBackground(Theme.BACKGROUND); list.setCellRenderer((view,id,index,selected,focus) -> renderRow(id,selected));
        JScrollPane items=new JScrollPane(list); items.setBorder(null); items.getViewport().setBackground(Theme.BACKGROUND);
        items.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listContent.add(items,"items");
        JPanel empty=Theme.panel(new GridBagLayout()); JPanel emptyWords=Theme.panel(new GridLayout(3,1,0,12));
        JLabel emptyIcon=Theme.label("○",40,false); emptyIcon.setForeground(new Color(0xC8C3B6)); emptyIcon.setHorizontalAlignment(SwingConstants.CENTER);
        emptyTitle.setHorizontalAlignment(SwingConstants.CENTER); emptyHint.setHorizontalAlignment(SwingConstants.CENTER); emptyHint.setForeground(Theme.MUTED);
        emptyWords.add(emptyIcon); emptyWords.add(emptyTitle); emptyWords.add(emptyHint); empty.add(emptyWords); listContent.add(empty,"empty");
        listPage.add(listContent,BorderLayout.CENTER); listPage.add(resume,BorderLayout.SOUTH); resume.setVisible(false);
        JPanel form=Theme.panel(new GridBagLayout()); GridBagConstraints g=new GridBagConstraints();
        g.gridx=0; g.gridy=0; g.weightx=1; g.fill=GridBagConstraints.HORIZONTAL; g.anchor=GridBagConstraints.NORTHWEST;
        g.insets=new Insets(0,0,16,0);
        formTitle.setName("plannerFormTitle"); formTitle.setFont(new Font("SansSerif",Font.BOLD,21));
        JPanel formHeading=Theme.panel(new BorderLayout(12,0)); formHeading.add(back,BorderLayout.WEST); formHeading.add(formTitle,BorderLayout.CENTER);
        formPage.add(formHeading,BorderLayout.NORTH);
        title.putClientProperty("placeholder","무엇을 함께할까요?"); title.setFont(new Font("SansSerif",Font.PLAIN,16));
        title.setToolTipText("제목은 1~80자, 한 줄로 입력해 주세요."); form.add(field("제목",title),g); g.gridy++;
        JPanel dates=Theme.panel(new GridLayout(1,2,12,0));
        date.setToolTipText("한국 시간 기준 · 과거 날짜도 등록할 수 있어요.");
        JPanel dateField=Theme.panel(new BorderLayout(0,7)); dateField.add(dateCaption,BorderLayout.NORTH); dateField.add(PlannerPickers.dateInput(date,()->{}),BorderLayout.CENTER);
        JPanel timeField=Theme.panel(new BorderLayout(0,7)); timeField.add(timeCaption,BorderLayout.NORTH); timeField.add(PlannerPickers.timeInput(time),BorderLayout.CENTER);
        dates.add(dateField); dates.add(timeField); form.add(dates,g); g.gridy++;
        assignee.setName("plannerAssignee"); assignee.setBackground(Color.WHITE);
        assignee.setRenderer((view,value,index,selected,focus) -> {
            JLabel label=Theme.label(value==null || value.isEmpty()?"담당자 선택 안 함":value+(members.contains(value)?"":" · 현재 방에 없음"),13,false);
            label.setBorder(new EmptyBorder(9,10,9,10)); label.setOpaque(true); label.setBackground(selected?new Color(0xFFF6CE):Color.WHITE); return label;
        });
        assigneeRow=field("담당자 · 선택",assignee); form.add(assigneeRow,g); g.gridy++;
        memoRow=field("메모 · 선택",textScroll(memo)); memo.setToolTipText("줄바꿈을 포함해 500자 이내"); form.add(memoRow,g); g.gridy++;
        source.setEditable(false); source.setBackground(new Color(0xF2F0EB));
        sourceRow=Theme.panel(new BorderLayout()); sourceRow.add(textScroll(source)); sourceRow.setVisible(false);
        sourceSection.add(sourceToggle,BorderLayout.NORTH); sourceSection.add(sourceRow,BorderLayout.CENTER); form.add(sourceSection,g); g.gridy++;
        latest.setEditable(false); latest.setBackground(new Color(0xFFF6CE));
        latestRow=field("최신 내용과 비교",textScroll(latest)); latestRow.setVisible(false); form.add(latestRow,g); g.gridy++;
        JPanel actions=Theme.panel(new FlowLayout(FlowLayout.RIGHT,6,0)); actions.add(delete); actions.add(complete); actions.add(save);
        JPanel formActions=Theme.panel(new BorderLayout(8,10));
        formActions.setBorder(new EmptyBorder(14,0,0,0)); formActions.add(discard,BorderLayout.WEST); formActions.add(actions,BorderLayout.EAST);
        formActions.add(review,BorderLayout.NORTH);
        g.weighty=1; form.add(Theme.panel(new BorderLayout()),g);
        JScrollPane formScroll=new JScrollPane(form); formScroll.setBorder(null); formScroll.getViewport().setBackground(Theme.BACKGROUND);
        formScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); formScroll.getVerticalScrollBar().setUnitIncrement(20);
        formPage.add(formScroll,BorderLayout.CENTER); formPage.add(formActions,BorderLayout.SOUTH);
        content.add(listPage,"list"); content.add(formPage,"form");
        choicePage.setName("plannerCreatePage");
        JButton choiceBack=quietButton("plannerChoiceBack","← 계획 목록"); choiceBack.addActionListener(e->showList());
        JPanel choiceNav=Theme.panel(new FlowLayout(FlowLayout.LEFT,0,0)); choiceNav.add(choiceBack); choicePage.add(choiceNav,BorderLayout.NORTH);
        JPanel choices=Theme.panel(new GridBagLayout()); JPanel cards=Theme.panel(new GridLayout(2,1,0,16));
        cards.add(choiceCard(addTodo,"대화에서 나온 부탁을 챙기고 완료를 표시해요."));
        cards.add(choiceCard(addSchedule,"함께할 날짜와 시간을 정해요.")); choices.add(cards);
        choicePage.add(choices,BorderLayout.CENTER); content.add(choicePage,"choose");
        JPanel guide=Theme.panel(new BorderLayout(0,20)); guide.setName("plannerSourceGuide");
        JButton guideBack=quietButton("plannerGuideBack","← 계획 목록"); guideBack.addActionListener(e->showList()); JPanel guideNav=Theme.panel(new FlowLayout(FlowLayout.LEFT,0,0)); guideNav.add(guideBack); guide.add(guideNav,BorderLayout.NORTH);
        JPanel guideWords=Theme.panel(new GridLayout(4,1,0,14));
        guideWords.add(Theme.label("대화에서 할 일을 가져와요",20,true));
        guideWords.add(Theme.label("채팅 메시지를 우클릭하고 ‘할 일로 만들기’를 선택하세요.",13,false));
        guideWords.add(Theme.label("원문을 함께 보관해 무엇을 부탁했는지 다시 볼 수 있어요.",12,false));
        JButton pickMessage=button("plannerPickMessage","대화에서 선택",true); pickMessage.addActionListener(e->leave()); guideWords.add(pickMessage);
        JPanel guideCenter=Theme.panel(new GridBagLayout()); guideCenter.add(guideWords); guide.add(guideCenter,BorderLayout.CENTER); content.add(guide,"sourceGuide");
        addTodo.addActionListener(e->{ showingForm=false; contentCards.show(content,"sourceGuide"); });
        addSchedule.addActionListener(e->{ kind="SCHEDULE"; newForm(); showForm(); }); add(content,BorderLayout.CENTER);
        feedback.setEditable(false); feedback.setOpaque(false); feedback.setFocusable(true);
        feedbackScroll.setName("plannerFeedbackScroll"); feedbackScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        feedbackScroll.getViewport().setBackground(Theme.BACKGROUND); feedbackScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        feedbackScroll.getVerticalScrollBar().setUnitIncrement(18); feedbackScroll.setVisible(false); feedback.setVisible(false); add(feedbackScroll,BorderLayout.SOUTH);
        close.addActionListener(e -> leave());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE,0),"closePlanner");
        getActionMap().put("closePlanner",new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ leave(); }});
        back.addActionListener(e -> { PlanKey previous=editKey(); if(!dirty && pending==null) newForm(); showList(); focusRow(previous); });
        resume.addActionListener(e -> showForm());
        sourceToggle.addActionListener(e -> { sourceRow.setVisible(!sourceRow.isVisible()); sourceToggle.setText(sourceRow.isVisible()?"원문 접기 ▴":"원문 보기 ▾"); revalidate(); repaint(); });
        refresh.addActionListener(e -> load());
        create.addActionListener(e -> {
            if(dirty) { say("작성 중인 내용이 있어요. 먼저 저장하거나 ‘입력 취소’를 눌러 주세요.",true); return; }
            showingForm=false; contentCards.show(content,"choose");
        });
        discard.addActionListener(e -> { newForm(); showList(); say("입력을 비웠어요.",false); });
        save.addActionListener(e -> save()); complete.addActionListener(e -> complete()); delete.addActionListener(e -> delete());
        review.addActionListener(e -> review());
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if(!SwingUtilities.isLeftMouseButton(e) || !list.isEnabled()) return;
                int index=list.locationToIndex(e.getPoint());
                if(index>=0 && list.getCellBounds(index,index).contains(e.getPoint())) {
                    list.setSelectedIndex(index); openSelected();
                }
            }
        });
        list.getAccessibleContext().setAccessibleDescription("방향키로 항목 이동, Enter로 상세 열기, Space로 할 일 완료 또는 취소");
        list.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,0),"openItem");
        list.getActionMap().put("openItem",new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){openSelected();} });
        list.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE,0,false),"quickComplete");
        list.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE,0,true),"releaseComplete");
        list.getActionMap().put("quickComplete",new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){
            if(spaceHeld) return; spaceHeld=true;
            if(listPage.isVisible() && list.isEnabled() && list.getSelectedValue()!=null)toggleFromList(list.getSelectedValue());
        }});
        list.getActionMap().put("releaseComplete",new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){spaceHeld=false;} });
        list.addFocusListener(new java.awt.event.FocusAdapter(){ public void focusLost(java.awt.event.FocusEvent e){spaceHeld=false;} });
        DocumentListener change=new DocumentListener() {
            public void insertUpdate(DocumentEvent e){ changed(); }
            public void removeUpdate(DocumentEvent e){ changed(); }
            public void changedUpdate(DocumentEvent e){ changed(); }
        };
        for(javax.swing.text.JTextComponent c:List.of(title,date,time,memo)) c.getDocument().addDocumentListener(change);
        assignee.addActionListener(e -> changed());
        newForm();
    }
    @Override public void addNotify() { super.addNotify(); dateRefresh.start(); }
    @Override public void removeNotify() { dateRefresh.stop(); super.removeNotify(); }
    private void openSelected() {
        PlanKey selected=list.getSelectedValue();
        if(!ready || pending!=null || !queuedLists.isEmpty() || state.room()==null || selected==null) return;
        if(dirty) {
            if(Objects.equals(editKey(),selected))showForm();
            else say("작성 중인 내용을 유지했어요. 먼저 저장하거나 입력을 취소한 뒤 다른 항목을 열어 주세요.",true);
            return;
        }
        showItem(selected);
    }
    private boolean contains(PlanKey key) {
        return key!=null && (key.kind().equals("TODO")?state.todo(key.id())!=null:state.schedule(key.id())!=null);
    }
    private void focusRow(PlanKey key) { focusRow(key,true); }
    private void focusRow(PlanKey key,boolean focus) {
        if(key==null || !rows.contains(key))return;
        list.setSelectedValue(key,false);
        String room=state.room();
        SwingUtilities.invokeLater(()->{
            if(!Objects.equals(room,state.room()) || !listPage.isVisible() || !Objects.equals(key,list.getSelectedValue()))return;
            list.ensureIndexIsVisible(list.getSelectedIndex());
            if(focus && isShowing() && list.isEnabled())list.requestFocusInWindow();
        });
    }
    private void revealSaved(PlanKey key,boolean focus) {
        if(!contains(key))return;
        if(!filter.getValue().isEmpty() && !filter.getValue().equals(rowDate(key)))filter.setValue("");
        focusRow(key,focus);
    }
    private PlanKey quickKeyAt(Point point) {
        int index=list.locationToIndex(point);
        if(index<0) return null;
        Rectangle cell=list.getCellBounds(index,index);
        if(cell==null || !cell.contains(point) || !new Rectangle(cell.x+10,cell.y+18,46,58).contains(point)) return null;
        PlanKey key=rows.get(index); return key.kind().equals("TODO")?key:null;
    }
    private boolean mayComplete(Todo todo) {
        return todo!=null && (Objects.equals(self,todo.author()) || Objects.equals(self,todo.assignee()));
    }
    private boolean canQuickComplete(Todo todo) {
        return ready && pending==null && queuedLists.isEmpty() && state.room()!=null && !quickNeedsRefresh && !dirty && !conflict && !removed && mayComplete(todo);
    }
    private void toggleFromList(PlanKey key) {
        Todo todo=state.todo(key.id());
        if(!key.kind().equals("TODO") || !canQuickComplete(todo)) return;
        list.setSelectedValue(key,false); quickCompletionId=todo.id();
        if(!send(Message.of("TODO_COMPLETE",UUID.randomUUID().toString(),todo.id(),""+todo.revision(),todo.done()?"0":"1"),"TODO_COMPLETED",todo.id()))quickCompletionId=null;
        list.repaint();
    }
    private void showList() {
        showingForm=false; contentCards.show(content,"list"); resume.setVisible(dirty || pending!=null);
        refreshRows(); revalidate(); repaint();
    }
    private void showForm() {
        showingForm=true; contentCards.show(content,"form"); revalidate(); repaint();
    }
    private void leave() {
        if(!showingForm) showList();
        returnToChat.run();
    }
    public void focusClose() { close.requestFocusInWindow(); }
    private void changed() { if(!updating) { dirty=true; deleteArmed=false; buttons(); } }
    public PlannerState state() { return state; }
    public boolean waiting() { return pending!=null; }
    public void enter(String room,String nickname) {
        pending=null; quickCompletionId=null; quickNeedsRefresh=false; spaceHeld=false; queuedLists.clear(); filter.setValue(""); state.enter(room); self=nickname; members=List.of(); kind="TODO"; listFresh=false; newForm(); refreshRows();
        say("메시지를 우클릭해 할 일로 만들거나, 추가 버튼으로 약속을 등록해 보세요.",false);
    }
    public void updateMembers(List<String> users) {
        members=List.copyOf(users); Object selected=assignee.getSelectedItem();
        updating=true; assignee.removeAllItems(); assignee.addItem("");
        for(String user:members) assignee.addItem(user);
        if(selected!=null && !selected.toString().isEmpty() && !members.contains(selected.toString())) assignee.addItem(selected.toString());
        assignee.setSelectedItem(selected==null?"":selected); updating=false; buttons();
    }
    public void setReady(boolean value) {
        ready=value;
        if(ready && pending==null) loadNext();
        buttons();
    }
    public void open() { load(); }
    public boolean fromMessage(String from,String text) {
        if(pending!=null || dirty) { say("작성 중인 내용이 있어요. 저장하거나 입력을 취소한 뒤 다시 선택해 주세요.",true); return false; }
        if(text.isBlank() || text.length()>1000 || PlannerState.controls(text,true)) { say("이 메시지는 원문으로 사용할 수 없어요.",true); return false; }
        kind="TODO"; newForm(); sourceSender=from; sourceText=text;
        updating=true; source.setText(from+"\n"+text);
        String summary=text.lines().findFirst().orElse("");
        if(summary.length()>80) { int end=80; if(Character.isHighSurrogate(summary.charAt(end-1))) end--; summary=summary.substring(0,end); }
        title.setText(summary); updating=false; dirty=true; formTitle.setText("대화에서 새 할 일");
        refreshRows(); showForm(); load(); buttons(); return true;
    }
    private void newForm() {
        updating=true; editId=null; editRevision=0; sourceSender=""; sourceText=""; dirty=false; conflict=false; removed=false; deleteArmed=false;
        title.setText(""); date.setText(""); time.setText(""); memo.setText(""); source.setText("메시지 우클릭 → 할 일로 만들기"); assignee.setSelectedItem(""); list.clearSelection();
        latest.setText(""); latestRow.setVisible(false); sourceRow.setVisible(false); sourceToggle.setText("원문 보기 ▾");
        updating=false; configure(); showList(); buttons();
    }
    private void configure() {
        boolean todo=kind.equals("TODO");
        formTitle.setText(editId==null?(todo?"대화에서 새 할 일":"새 약속"):(todo?"할 일 상세":"약속 상세"));
        assigneeRow.setVisible(todo); sourceSection.setVisible(todo); memoRow.setVisible(!todo);
        dateCaption.setText(todo?"마감일 · 선택":"진행일");
        timeCaption.setText(todo?"마감 시간 · 선택":"진행 시간 · 선택");
        back.setText("← 계획 목록"); create.setText("+ 추가");
        create.setToolTipText("할 일 또는 약속을 추가해요.");
        emptyTitle.setText("아직 계획이 없어요"); emptyHint.setText("대화 속 할 일이나 함께할 약속을 남겨 보세요.");
        revalidate(); repaint();
    }
    private void filterChanged() {
        String validation=PlannerState.validate("조회",filter.getValue().strip(),"","",false);
        if(validation!=null) { say(validation,true); return; }
        refreshRows();
    }
    private void load() {
        if(state.room()==null) return;
        // 목록에는 requestId가 없으므로 두 종류를 순차 조회한다. 필터는 합친 목록에 적용한다.
        queuedLists.clear(); queuedLists.add("TODO"); queuedLists.add("SCHEDULE"); listFresh=false;
        loadNext(); buttons();
    }
    private void loadNext() {
        if(!ready || pending!=null || state.room()==null || queuedLists.isEmpty()) return;
        String next=queuedLists.removeFirst();
        Message request=next.equals("TODO")?Message.of("TODO_LIST",state.room()):Message.of("SCHEDULE_LIST",state.room(),"");
        state.expectList(next,"");
        if(!send(request,next+"_LIST_END",null)) { state.cancelList(); queuedLists.clear(); }
    }
    private boolean send(Message m,String response,String entity) {
        if(!ready || pending!=null) return false;
        pending=new Pending(m,response,entity); deleteArmed=false;
        if(!sender.apply(m)) { pending=null; say("다른 요청을 처리 중이에요. 잠시 후 다시 시도해 주세요.",true); buttons(); return false; }
        say("서버에서 확인하고 있어요…",false); buttons(); return true;
    }
    private void save() {
        if(!ready || pending!=null || conflict || removed || !canEdit()) return;
        String t=title.getText().strip(), d=date.getText().strip(), ti=time.getText().strip(), note=memo.getText();
        String error=PlannerState.validate(t,d,ti,note,kind.equals("SCHEDULE"));
        if(error!=null) { say(error,true); return; }
        String who=Objects.toString(assignee.getSelectedItem(),"");
        Todo old=editId==null?null:state.todo(editId);
        if(kind.equals("TODO")) {
            if(!who.isEmpty() && !members.contains(who) && (old==null || !old.assignee().equals(who))) { say("담당자는 현재 방에 있는 사람을 선택해 주세요.",true); return; }
            if(sourceSender.isEmpty() || sourceText.isBlank()) { say("대화에서 메시지를 선택해 할 일을 만들어 주세요.",true); return; }
        }
        String id=UUID.randomUUID().toString();
        Message request;
        if(kind.equals("TODO")) request=editId==null?
                Message.of("TODO_CREATE",id,state.room(),t,sourceSender,sourceText,who,d,ti):
                Message.of("TODO_EDIT",id,editId,""+editRevision,t,who,d,ti);
        else request=editId==null?Message.of("SCHEDULE_CREATE",id,state.room(),d,t,ti,note):
                Message.of("SCHEDULE_EDIT",id,editId,""+editRevision,d,t,ti,note);
        send(request,kind+(editId==null?"_CREATED":"_EDITED"),editId);
    }
    private void complete() {
        Todo item=state.todo(editId);
        if(!kind.equals("TODO") || !ready || pending!=null || conflict || dirty || removed || item==null || !(self.equals(item.author()) || self.equals(item.assignee()))) return;
        send(Message.of("TODO_COMPLETE",UUID.randomUUID().toString(),editId,""+editRevision,item.done()?"0":"1"),"TODO_COMPLETED",editId);
    }
    private void delete() {
        if(!ready || pending!=null || conflict || dirty || removed || editId==null || !canEdit()) return;
        if(!deleteArmed) { deleteArmed=true; say("이 항목을 삭제할까요? ‘삭제 확인’을 한 번 더 누르면 삭제합니다.",true); buttons(); return; }
        send(Message.of(kind+"_DELETE",UUID.randomUUID().toString(),editId,""+editRevision),kind+"_DELETED",editId);
    }
    private void review() {
        if(!ready || pending!=null || editId==null) return;
        if(!listFresh) { load(); return; }
        Todo t=state.todo(editId); Schedule s=state.schedule(editId);
        if((kind.equals("TODO")?t:s)==null) { removed=true; say("현재 목록에서 항목을 찾을 수 없어요. 삭제된 항목은 수정할 수 없어요.",true); buttons(); return; }
        editRevision=kind.equals("TODO")?t.revision():s.revision(); conflict=false; removed=false;
        String latest=kind.equals("TODO")?t.title():s.title();
        this.latest.setText(kind.equals("TODO")?
                "제목: "+t.title()+"\n담당자: "+(t.assignee().isEmpty()?"없음":t.assignee())+"\n마감: "+t.date()+" "+t.time()+"\n완료: "+(t.done()?"완료":"미완료"):
                "제목: "+s.title()+"\n일시: "+s.date()+" "+s.time()+"\n메모: "+s.memo());
        latestRow.setVisible(true); latestRow.revalidate();
        SwingUtilities.invokeLater(()->this.latest.scrollRectToVisible(new Rectangle(0,0,this.latest.getWidth(),this.latest.getHeight())));
        say("최신 버전 "+editRevision+" 확인 · 서버 제목: "+latest+"\n작성 중인 입력은 유지했어요. 비교 후 ‘저장’을 눌러 주세요.",false); buttons();
    }
    /** true면 ChatPanel이 전체 요청 대기를 끝낼 수 있다. 다른 방·다른 requestId는 끝내지 않는다. */
    public boolean receive(Message m) throws IOException {
        boolean ended=state.accept(m), matched=false;
        PlanKey saved=null; boolean quick=quickCompletionId!=null;
        if(pending!=null && pending.response().equals(m.type())) {
            if(pending.request().type().endsWith("_LIST")) matched=ended;
            else matched=m.fields().size()>=3 && Objects.equals(state.room(),m.field(0)) && pending.request().field(0).equals(m.field(1))
                    && (pending.entity()==null || pending.entity().equals(m.field(2)));
        }
        if(matched) {
            if(m.type().endsWith("_CREATED") || m.type().endsWith("_EDITED") || m.type().equals("TODO_COMPLETED"))
                saved=new PlanKey(m.type().startsWith("TODO_")?"TODO":"SCHEDULE",m.field(2));
            boolean query=pending.request().type().endsWith("_LIST"); pending=null;
            if(query) { listFresh=queuedLists.isEmpty(); if(listFresh)quickNeedsRefresh=false; say(conflict?"최신 목록을 받았어요. ‘최신 내용 확인’을 눌러 비교해 주세요.":"목록을 불러왔어요.",false); }
            else if(quickCompletionId!=null) {
                if(Objects.equals(editKey(),new PlanKey("TODO",quickCompletionId)) && state.todo(quickCompletionId)!=null)
                    editRevision=state.todo(quickCompletionId).revision();
                quickCompletionId=null; say("반영했어요.",false);
            } else { newForm(); say("반영했어요.",false); }
        }
        if(editId!=null) {
            Todo t=state.todo(editId); Schedule s=state.schedule(editId);
            long rev=kind.equals("TODO")?(t==null?0:t.revision()):(s==null?0:s.revision());
            if(rev>editRevision) { conflict=true; say("다른 변경이 도착했어요. 입력은 유지했으니 최신 내용을 확인해 주세요.",true); }
            if(m.type().equals(kind+"_DELETED") && m.field(0).equals(state.room()) && m.field(2).equals(editId)) { removed=true; say("이 항목은 삭제됐어요. 작성 중인 입력은 남겨 두었어요.",true); }
        }
        refreshRows(); if(saved!=null && (!quick || Objects.equals(saved,list.getSelectedValue())))revealSaved(saved,!quick); buttons(); return matched;
    }
    public boolean error(Message m) throws IOException {
        if(m.fields().size()!=2 && m.fields().size()!=3) throw new IOException("ERROR 필드 수 오류");
        if(pending==null) return false;
        boolean query=pending.request().type().endsWith("_LIST");
        if(query ? m.fields().size()!=2 : m.fields().size()!=3 || (!m.field(2).isEmpty() && !pending.request().field(0).equals(m.field(2)))) return false;
        pending=null; state.cancelList(); queuedLists.clear(); listFresh=false; deleteArmed=false;
        if(quickCompletionId!=null) {
            quickCompletionId=null; quickNeedsRefresh=true;
            say(m.field(1)+"\n완료 상태를 바꾸지 못했어요. 새로고침한 뒤 다시 시도해 주세요.",true);
            buttons(); return true;
        }
        if(m.field(0).equals("CONFLICT")) { conflict=true; listFresh=false; }
        if(m.field(0).endsWith("_NOT_FOUND")) removed=true;
        say((query?"계획을 모두 불러오지 못했어요. 새로고침해 주세요.\n":"")+m.field(1)+" ("+m.field(0)+")\n입력은 유지했어요."+(conflict?" 목록 갱신 후 최신 내용을 확인해 주세요.":""),true);
        buttons(); return true;
    }
    private PlanKey editKey() { return editId==null?null:new PlanKey(kind,editId); }
    private void showItem(PlanKey key) {
        String id=key.id(); kind=key.kind();
        updating=true; editId=id; dirty=false; conflict=false; removed=false; deleteArmed=false;
        if(kind.equals("TODO")) {
            Todo t=state.todo(id); if(t==null) { updating=false; return; }
            editRevision=t.revision(); title.setText(t.title()); date.setText(t.date()); time.setText(t.time());
            sourceSender=t.sourceSender(); sourceText=t.sourceText(); source.setText(sourceSender+"\n"+sourceText);
            if(!t.assignee().isEmpty() && !members.contains(t.assignee())) assignee.addItem(t.assignee());
            assignee.setSelectedItem(t.assignee());
        } else {
            Schedule s=state.schedule(id); if(s==null) { updating=false; return; }
            editRevision=s.revision(); title.setText(s.title()); date.setText(s.date()); time.setText(s.time()); memo.setText(s.memo());
        }
        latest.setText(""); latestRow.setVisible(false); sourceRow.setVisible(false); sourceToggle.setText("원문 보기 ▾");
        updating=false; configure(); showForm(); say(canEdit()?"내용을 수정한 뒤 저장해 주세요.":"작성자만 수정·삭제할 수 있어요.",false); buttons();
    }
    private boolean canEdit() {
        if(editId==null) return true;
        if(kind.equals("TODO")) { Todo t=state.todo(editId); return t!=null && Objects.equals(self,t.author()); }
        Schedule s=state.schedule(editId); return s!=null && Objects.equals(self,s.author());
    }
    private void buttons() {
        boolean available=ready && pending==null && queuedLists.isEmpty() && state.room()!=null, editable=available && canEdit() && !removed;
        refresh.setEnabled(available); addTodo.setEnabled(available); addSchedule.setEnabled(available);
        create.setEnabled(available); discard.setEnabled(available); list.setEnabled(state.room()!=null);
        for(JComponent c:List.of(title,date,time,memo,assignee)) c.setEnabled(editable);
        save.setVisible(editId==null || canEdit()); save.setText(editId==null?"추가하기":"저장"); save.setEnabled(editable && !conflict && (kind.equals("SCHEDULE") || !sourceText.isEmpty()));
        delete.setVisible(editId!=null && canEdit()); delete.setText(deleteArmed?"삭제 확인":"삭제"); delete.setEnabled(editable && !conflict && !dirty);
        Todo t=editId==null?null:state.todo(editId);
        complete.setVisible(kind.equals("TODO") && t!=null && (Objects.equals(self,t.author()) || Objects.equals(self,t.assignee()))); complete.setText(t!=null && t.done()?"완료 취소":"완료로 표시");
        complete.setEnabled(kind.equals("TODO") && available && !dirty && !conflict && !removed && t!=null && (Objects.equals(self,t.author()) || Objects.equals(self,t.assignee())));
        review.setVisible(conflict || removed); review.setEnabled(available);
        resume.setVisible(!showingForm && (dirty || pending!=null));
        close.setEnabled(true); back.setEnabled(true);
        discard.setVisible(dirty || editId==null);
        revalidate(); repaint();
    }
    private String rowDate(PlanKey key) {
        return key.kind().equals("TODO")?state.todo(key.id()).date():state.schedule(key.id()).date();
    }
    private String rowTime(PlanKey key) {
        return key.kind().equals("TODO")?state.todo(key.id()).time():state.schedule(key.id()).time();
    }
    private boolean rowDone(PlanKey key) { return key.kind().equals("TODO") && state.todo(key.id()).done(); }
    private void refreshRows() {
        PlanKey selected=list.getSelectedValue();
        updating=true; rows.clear(); List<PlanKey> all=new ArrayList<>();
        for(Todo t:state.todos()) all.add(new PlanKey("TODO",t.id()));
        for(Schedule s:state.schedules()) all.add(new PlanKey("SCHEDULE",s.id()));
        String query=filter.getValue().strip();
        all.stream().filter(key->query.isEmpty() || rowDate(key).equals(query))
                .sorted(Comparator.comparing(this::rowDone)
                    .thenComparing(key->rowDate(key).isEmpty()?"9999-12-31":rowDate(key))
                    .thenComparing(key->rowTime(key).isEmpty()?"99:99":rowTime(key))
                    .thenComparing(PlanKey::kind).thenComparing(PlanKey::id))
                .forEach(rows::addElement);
        if(selected!=null && rows.contains(selected))list.setSelectedValue(selected,false); updating=false;
        count.setText((query.isEmpty()?"함께 챙길 계획":shortDate(query)+"의 계획")+" · "+rows.size());
        emptyTitle.setText(query.isEmpty()?"아직 계획이 없어요":"이날의 계획이 없어요");
        emptyHint.setText(query.isEmpty()?"대화 속 할 일이나 함께할 약속을 남겨 보세요.":"날짜 옆 ×를 눌러 다른 날의 계획도 확인해 보세요.");
        listCards.show(listContent,rows.isEmpty()?"empty":"items");
    }
    private Component renderRow(PlanKey key,boolean selected) {
        boolean todo=key.kind().equals("TODO"); Todo t=todo?state.todo(key.id()):null; Schedule s=todo?null:state.schedule(key.id());
        boolean done=t!=null && t.done();
        JPanel cell=new JPanel(new BorderLayout(14,0)) {
            @Override protected void paintComponent(Graphics graphics) {
                doLayout(); for(Component child:getComponents()) if(child instanceof Container container) container.doLayout();
                Graphics2D g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(selected?new Color(0xFFF7D8):Color.WHITE); g.fillRoundRect(0,4,getWidth()-1,getHeight()-8,20,20); g.dispose();
            }
        }; cell.setOpaque(false); cell.setBorder(new EmptyBorder(18,18,18,18));
        String heading=t!=null?t.title():s!=null?s.title():"";
        java.time.LocalDateTime now=PlannerDates.now();
        String sub="";
        if(t!=null) {
            sub="할 일 · "+(t.done()?"완료":"진행 중")+" · "+(t.assignee().isEmpty()?"담당자 미정":t.assignee());
            if(!t.date().isEmpty()) sub+=" · "+PlannerDates.todo(t.date(),t.time(),t.done(),now);
            boolean processing=Objects.equals(quickCompletionId,t.id());
            if(processing)sub="완료 상태를 바꾸는 중…";
            JLabel status=Theme.label("",24,false); status.setPreferredSize(new Dimension(30,40));
            status.setIcon(completionIcon(done,canQuickComplete(t),processing));
            status.getAccessibleContext().setAccessibleName(done?"완료 취소":"완료로 표시"); cell.add(status,BorderLayout.WEST);
        } else if(s!=null) {
            sub="약속 · "+PlannerDates.schedule(s.date(),s.time(),now);
            cell.add(dateBadge(s.date()),BorderLayout.WEST);
        }
        JPanel text=Theme.panel(new GridLayout(2,1,0,7)); JLabel main=Theme.label(heading,15,true), detail=Theme.label(sub,12,false);
        if(done) main.setForeground(Theme.MUTED);
        detail.setForeground(t!=null && !done && PlannerDates.past(t.date(),t.time(),now)?new Color(0xA66A43):todo?Theme.MUTED:new Color(0x537781)); detail.setFont(new Font("SansSerif",todo?Font.PLAIN:Font.BOLD,12)); main.setToolTipText(heading);
        text.add(main); text.add(detail); cell.add(text,BorderLayout.CENTER);
        JLabel arrow=Theme.label("›",24,false); arrow.setForeground(new Color(0xB8B3A8)); cell.add(arrow,BorderLayout.EAST);
        return cell;
    }
    private static Icon completionIcon(boolean done,boolean enabled,boolean processing) {
        return new Icon() {
            public int getIconWidth(){return 26;} public int getIconHeight(){return 26;}
            public void paintIcon(Component component,Graphics graphics,int x,int y) {
                Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.7f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.setColor(enabled?new Color(0x7E9985):new Color(0xC9CDC7));
                if(done){g.fillOval(x+2,y+2,22,22); g.setColor(Color.WHITE); g.drawLine(x+7,y+13,x+11,y+17);g.drawLine(x+11,y+17,x+19,y+9);}
                else g.drawOval(x+2,y+2,22,22);
                if(processing){g.setColor(Theme.MUTED);g.drawArc(x+6,y+6,14,14,25,260);}
                g.dispose();
            }
        };
    }
    private static JComponent dateBadge(String date) {
        return new JComponent() {
            { setPreferredSize(new Dimension(58,54)); setName("scheduleDateBadge"); setToolTipText(date); }
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0xE9F1F2)); g.fillRoundRect(0,0,getWidth(),getHeight(),14,14);
                try {
                    java.time.LocalDate d=java.time.LocalDate.parse(date);
                    String month=d.getMonthValue()+"월", day=""+d.getDayOfMonth();
                    g.setColor(new Color(0x537781)); g.setFont(new Font("SansSerif",Font.PLAIN,11));
                    g.drawString(month,(getWidth()-g.getFontMetrics().stringWidth(month))/2,17);
                    g.setFont(new Font("SansSerif",Font.BOLD,23)); g.drawString(day,(getWidth()-g.getFontMetrics().stringWidth(day))/2,43);
                } catch(java.time.DateTimeException ignored) { }
                g.dispose();
            }
        };
    }
    private static String shortDate(String date) {
        try { java.time.LocalDate d=java.time.LocalDate.parse(date); return d.getMonthValue()+"월 "+d.getDayOfMonth()+"일"; }
        catch(java.time.DateTimeException e) { return date; }
    }
    private void say(String text,boolean error) {
        feedback.setToolTipText(text);
        feedback.setText(text.replaceAll(" \\([A-Z_]+\\)",""));
        feedback.setForeground(error?new Color(0xA33F2D):Theme.MUTED);
        boolean visible=error || conflict || removed || text.startsWith("최신 버전");
        feedback.setVisible(visible); feedbackScroll.setVisible(visible); feedback.setCaretPosition(0);
        revalidate(); repaint();
    }
    private static JPanel field(String label,JComponent input) {
        JPanel p=Theme.panel(new BorderLayout(0,7)); p.add(Theme.label(label,12,false),BorderLayout.NORTH); p.add(input,BorderLayout.CENTER); return p;
    }
    private static JButton button(String name,String label,boolean primary) {
        JButton b=Theme.button(label,primary); b.setName(name); b.setBorder(new EmptyBorder(9,11,9,11)); return b;
    }
    private static JButton quietButton(String name,String text) {
        JButton b=new JButton(text); b.setName(name); b.putClientProperty("html.disable",true);
        b.setFont(new Font("SansSerif",Font.PLAIN,13)); b.setForeground(Theme.MUTED);
        b.setBorder(new EmptyBorder(8,10,8,10)); b.setContentAreaFilled(false); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }
    private static JPanel choiceCard(JButton action,String hint) {
        JPanel card=Theme.rounded(new BorderLayout(0,12),Color.WHITE,20); card.setBorder(new EmptyBorder(22,24,22,24));
        action.setFont(new Font("SansSerif",Font.BOLD,18)); card.add(action,BorderLayout.NORTH);
        JLabel caption=Theme.label(hint,13,false); caption.setForeground(Theme.MUTED); card.add(caption,BorderLayout.CENTER); return card;
    }
    private static JTextArea area(String name,int rows) {
        JTextArea a=new JTextArea(rows,10); a.setName(name); a.setLineWrap(true); a.setWrapStyleWord(true); a.setFont(new Font("SansSerif",Font.PLAIN,12)); a.setBorder(new EmptyBorder(8,8,8,8)); return a;
    }
    private static JScrollPane textScroll(JTextArea area) {
        JScrollPane scroll=new JScrollPane(area); scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER)); return scroll;
    }
}
