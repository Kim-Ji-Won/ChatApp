package chatapp;

import chatapp.client.ui.*;
import chatapp.client.planner.PlannerState;
import chatapp.client.network.*;
import chatapp.common.Message;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 3주차 프론트 검증. TestPeer는 응답 순서를 조작하는 TCP 상대이며 실제 백엔드가 아니다. */
public final class Week3Checks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        stateChecks(); inputChecks(); relativeDateChecks(); pickerChecks(); messageMenuChecks(); demoChecks(); demoUiChecks(); tcpChecks(); quickCompleteChecks();
        System.out.println("PASS WEEK3: "+checks+" checks. 실제 3주차 백엔드 통합은 별도 확인 필요.");
    }
    private static void stateChecks() throws Exception {
        PlannerState s=new PlannerState(); s.enter("r1");
        s.expectList("TODO",""); s.accept(Message.of("TODO_LIST_START","r1","1"));
        s.accept(todo("TODO_ITEM","","","t1","1","기존 제목","지원","지원","0"));
        check(s.todos().isEmpty(),"END 전에는 부분 목록을 표시하지 않음");
        check(s.accept(Message.of("TODO_LIST_END","r1")) && s.todos().size()==1,"완전한 목록만 반영");
        s.accept(todo("TODO_EDITED","other","x","t1","9","다른 방","지원","지원","0"));
        check(s.todo("t1").revision()==1,"다른 방 변경 알림 차단");
        s.accept(todo("TODO_EDITED","r1","x","t1","3","새 제목","지원","지원","0"));
        s.accept(todo("TODO_EDITED","r1","x","t1","2","과거 제목","지원","지원","0"));
        check(s.todo("t1").title().equals("새 제목"),"낮은 revision 재전송으로 최신 내용 롤백 금지");
        s.accept(Message.of("TODO_DELETED","r1","d","t1"));
        s.accept(todo("TODO_CREATED","r1","x","t1","1","되살리기","지원","지원","0"));
        check(s.todos().isEmpty(),"삭제 뒤 과거 생성 응답으로 부활 금지");
        s.accept(Message.of("TODO_COMPLETED","r1","done","unknown","4","1"));
        s.accept(todo("TODO_CREATED","r1","old","unknown","1","오래된 생성","지원","지원","0"));
        check(s.todo("unknown")==null,"아직 조회하지 않은 항목도 완료 버전보다 오래된 생성 무시");
        s.expectList("TODO",""); s.accept(Message.of("TODO_LIST_START","r1","0")); s.accept(Message.of("TODO_LIST_END","r1"));
        check(s.todos().isEmpty(),"빈 목록 START 0 / END 처리");
        s.expectList("SCHEDULE","2026-11-19"); s.accept(Message.of("SCHEDULE_LIST_START","r1","1"));
        s.accept(Message.of("SCHEDULE_ITEM","s1","1","2026-11-19","약속","12:00","메모\n두 줄","지원"));
        s.accept(Message.of("SCHEDULE_LIST_END","r1"));
        s.accept(Message.of("SCHEDULE_EDITED","r1","e","s1","2","2026-11-20","변경된 약속","12:00","","지원"));
        check(s.schedules().isEmpty(),"날짜 필터 밖으로 이동한 일정 제거");
        s.accept(Message.of("SCHEDULE_EDITED","r1","e","s1","1","2026-11-19","과거","12:00","","지원"));
        check(s.schedules().isEmpty(),"날짜 필터에서도 이전 버전 응답 무시");
        s.accept(Message.of("SCHEDULE_EDITED","r1","e","s1","3","2026-11-19","다시 오늘","12:00","","지원"));
        check(s.schedules().size()==1,"필터 안으로 이동한 일정 추가");
        s.expectList("TODO",""); s.accept(Message.of("TODO_LIST_START","r1","1"));
        try { s.accept(Message.of("TODO_LIST_END","r1")); throw new AssertionError("불완전 목록 허용"); }
        catch(IOException expected) { check(true,"목록 개수 불일치 검출"); }
        s.enter("r2"); s.accept(todo("TODO_EDITED","r1","x","t1","5","이전 방","지원","지원","0"));
        check(s.todos().isEmpty() && s.schedules().isEmpty(),"방 전환 시 목록·수집 상태 격리");
        s.expectList("TODO",""); s.accept(Message.of("TODO_LIST_START","r2","2"));
        s.accept(todo("TODO_ITEM","","","t1","1","중복","지원","지원","0"));
        try { s.accept(todo("TODO_ITEM","","","t1","1","중복","지원","지원","0")); throw new AssertionError("중복 ID 허용"); }
        catch(IOException expected) { check(true,"중복 목록 ID 검출"); }
    }
    private static void inputChecks() {
        check(PlannerState.validate("제목","2024-02-29","23:59","두 줄\n메모",true)==null,"윤년·24시간·메모 줄바꿈 허용");
        check(PlannerState.validate("제목","2026-02-29","","",true)!=null,"존재하지 않는 날짜 거부");
        check(PlannerState.validate("제목","2026-11-19","24:00","",true)!=null,"24:00 거부");
        check(PlannerState.validate("제목","","13:00","",false)!=null,"날짜 없이 마감 시간만 지정 금지");
        check(PlannerState.validate("제목","","","",false)==null,"할 일 마감일 선택 입력");
        check(PlannerState.validate("제목","","","",true)!=null,"일정 날짜 필수");
        check(PlannerState.validate("줄\n바꿈","","","",false)!=null,"제목 줄바꿈 거부");
        check(PlannerState.validate("가".repeat(81),"","","",false)!=null,"80자 제목 제한");
        check(PlannerState.validate("약속","2000-01-01","","",true)==null,"과거 날짜 허용");
        check(PlannerState.validate("약속","2026-11-19","","\t",true)!=null,"메모의 탭 제어문자 거부");
        check(PlannerState.validate("약속","2026-11-19","","가".repeat(501),true)!=null,"메모 500자 제한");
    }
    private static void messageMenuChecks() throws Exception {
        edt(() -> {
            ConversationView v=new ConversationView(); v.setSize(620,500);
            List<String> selected=new ArrayList<>(); v.setTaskHandler((from,text)->selected.add(from+":"+text));
            v.addTaskMessage("지원","내일 자료 같이 확인해요",true);
            v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_PRESSED,0,0,560,25,1,false,MouseEvent.BUTTON3));
            JMenuItem item=(JMenuItem)v.getComponentPopupMenu().getComponent(0); item.doClick();
            check(selected.equals(List.of("지원:내일 자료 같이 확인해요")),"말풍선 위치에서 원문·작성자 사본 전달");
            v.clear(); v.addMessage("귓속말 · 친구","비공개",true);
            v.dispatchEvent(new MouseEvent(v,MouseEvent.MOUSE_PRESSED,0,0,570,25,1,false,MouseEvent.BUTTON3));
            check(!item.isEnabled(),"귓속말은 공유 할 일 원문으로 선택 불가");
            return null;
        });
    }
    private static void relativeDateChecks() {
        java.time.LocalDateTime now=java.time.LocalDateTime.of(2026,11,19,15,0);
        check(PlannerDates.todo("2026-11-19","",false,now).equals("오늘까지"),"시간 없는 오늘 마감은 하루가 지나기 전까지 경고하지 않음");
        check(PlannerDates.todo("2026-11-20","15:05",false,now).equals("내일 오후 3시 5분까지"),"내일·오후·분 단위 마감 표현");
        check(PlannerDates.todo("2026-11-18","",false,now).equals("마감 지남 · 어제"),"미완료인 지난 날짜는 마감 지남 표시");
        check(PlannerDates.todo("2026-11-19","14:59",false,now).startsWith("마감 지남"),"오늘도 지정한 마감 시간이 지나면 경고");
        check(!PlannerDates.todo("2026-11-19","15:00",false,now).contains("마감 지남"),"현재 마감 분은 지나지 않은 것으로 표시");
        check(!PlannerDates.todo("2026-11-18","",true,now).contains("마감 지남"),"완료한 할 일에는 지난 마감 경고를 붙이지 않음");
        check(PlannerDates.todo("","",false,now).isEmpty(),"마감일 없는 할 일에 날짜를 만들어 표시하지 않음");
        check(PlannerDates.schedule("2026-11-20","15:00",now).equals("내일 오후 3시"),"다가오는 약속을 상대 날짜로 표시");
        check(PlannerDates.schedule("2026-11-18","12:00",now).equals("지난 약속 · 어제 오후 12시"),"지난 약속과 정오 표현");
        check(PlannerDates.schedule("2026-11-20","00:00",now).equals("내일 오전 12시"),"자정은 오전 12시로 표시");
        check(PlannerDates.schedule("2026-11-19","",now).equals("오늘 · 시간 미정"),"시간 미정 약속은 당일에 지난 약속으로 단정하지 않음");
        check(PlannerDates.absolute("2025-11-20",now.toLocalDate()).equals("2025년 11월 20일"),"다른 연도에는 연도를 표시");
        check(PlannerDates.todo("2026-11-19","",false,now.plusDays(1).withHour(0)).startsWith("마감 지남"),"날짜가 바뀌면 하루 마감도 지난 상태로 갱신");
        check(PlannerDates.todo("잘못된 날짜","",false,now).equals("잘못된 날짜까지") && !PlannerDates.past("잘못된 날짜","",now),"잘못된 서버 날짜도 화면 렌더링 예외를 일으키지 않음");
        check(PlannerDates.schedule("2026-11-20","99:99",now).contains("99:99"),"잘못된 서버 시간은 임의로 보정하지 않고 원문 표시");
    }
    private static void pickerChecks() throws Exception {
        edt(()->{
            Theme.install(); List<String> values=new ArrayList<>();
            JPanel calendar=PlannerPickers.calendar("2028-02-12",values::add);
            check(find(calendar,"calendarDay29")!=null && find(calendar,"calendarDay30")==null,"달력은 윤년의 실제 날짜만 표시");
            ((JButton)find(calendar,"calendarDay29")).doClick();
            check(values.equals(List.of("2028-02-29")),"달력 선택을 YYYY-MM-DD 형식으로 변환");
            ((JButton)find(calendar,"calendarNext")).doClick();
            check(((JLabel)find(calendar,"calendarMonth")).getText().equals("2028년 3월") && values.size()==1,"달 이동만으로 입력을 변경하지 않음");
            ((JButton)find(calendar,"calendarPrevious")).doClick(); ((JButton)find(calendar,"calendarDay1")).doClick();
            check(values.get(1).equals("2028-02-01"),"이전 달 이동 후 선택 월과 일 유지");
            JPanel boundary=PlannerPickers.calendar("2026-12-31",values::add);
            ((JButton)find(boundary,"calendarNext")).doClick(); ((JButton)find(boundary,"calendarDay2")).doClick();
            check(values.get(2).equals("2027-01-02"),"연말 달 이동에서 다음 연도로 전환");
            ((JButton)find(boundary,"calendarClear")).doClick(); check(values.get(3).isEmpty(),"마감일을 선택하지 않은 상태로 되돌리기");
            ((JButton)find(boundary,"calendarToday")).doClick();
            check(values.get(4).equals(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()),"오늘 선택은 한국 날짜 기준");
            JPanel nonLeap=PlannerPickers.calendar("2026-02-12",values::add);
            check(find(nonLeap,"calendarDay29")==null,"평년 2월에는 29일을 표시하지 않음");
            int before=values.size(); JPanel time=PlannerPickers.clock("09:07",values::add);
            check(((JSpinner)find(time,"timeHours")).getValue().equals(9) && ((JSpinner)find(time,"timeMinutes")).getValue().equals(7),"시간 선택에서 기존 시·분을 정확히 복원");
            ((JSpinner)find(time,"timeHours")).setValue(23); ((JSpinner)find(time,"timeMinutes")).setValue(59);
            check(values.size()==before,"시·분 조정은 선택 버튼을 누르기 전까지 초안에 미반영");
            ((JButton)find(time,"timeApply")).doClick(); check(values.get(before).equals("23:59"),"선택한 시간을 HH:mm으로 변환");
            ((JSpinner)find(time,"timeHours")).setValue(0); ((JSpinner)find(time,"timeMinutes")).setValue(0);
            ((JButton)find(time,"timeApply")).doClick(); check(values.get(before+1).equals("00:00"),"자정 시간도 00:00으로 유지");
            ((JButton)find(time,"timeClear")).doClick(); check(values.get(before+2).isEmpty(),"시간 미정으로 선택 해제");
            JTextField field=Theme.field("testDate",""); JPanel input=PlannerPickers.dateInput(field,()->{}); field.setEnabled(false);
            check(!find(input,"testDatePicker").isEnabled() && !field.isEditable(),"권한 없는 날짜 필드는 달력 버튼도 비활성화");
            JSpinner hours=(JSpinner)find(time,"timeHours"), minutes=(JSpinner)find(time,"timeMinutes");
            int selectedCount=values.size();
            ((JButton)find(hours,"Spinner.nextButton")).doClick(); ((JButton)find(minutes,"Spinner.nextButton")).doClick();
            check(hours.getValue().equals(1) && minutes.getValue().equals(1) && values.size()==selectedCount,"시·분 화살표는 팝업을 추가로 열거나 확정하지 않고 값만 변경");
            ((JButton)find(hours,"Spinner.previousButton")).doClick(); check(hours.getValue().equals(0),"시간 내림 화살표 동작");
            check(hours.getPreviousValue()==null,"0시 아래로 벗어나지 않음");
            minutes.setValue(59); check(minutes.getNextValue()==null,"59분 위로 벗어나지 않음");
            JFormattedTextField hourInput=((JSpinner.NumberEditor)hours.getEditor()).getTextField();
            hourInput.setText("24"); ((JButton)find(time,"timeApply")).doClick();
            check(values.size()==selectedCount && find(time,"timeError").isVisible(),"범위 밖 직접 입력은 확정하지 않고 팝업 안에서 안내");
            hourInput.setText("23"); ((JButton)find(time,"timeApply")).doClick();
            check(values.get(values.size()-1).equals("23:59") && !find(time,"timeError").isVisible(),"직접 입력을 고치면 HH:mm 형식으로 확정");
            snapshotComponent(calendar,"week3-calendar-picker",336,362); snapshotComponent(time,"week3-time-picker",280,216);
            return null;
        });
    }
    private static void snapshotComponent(JComponent component,String name,int width,int height) throws IOException {
        component.setSize(width,height); layout(component); BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics(); component.printAll(g); g.dispose(); Files.createDirectories(Path.of("build/screenshots"));
        ImageIO.write(image,"png",Path.of("build/screenshots/"+name+".png").toFile());
    }
    private static void demoChecks() throws Exception {
        DemoTransport d=new DemoTransport(); List<Message> out=new ArrayList<>();
        try {
            d.connect("",0,"지원",new ChatTransport.Listener(){ public void onMessage(Message m){out.add(m);} public void onDisconnected(String s){} });
            d.send(Message.of("ROOM_JOIN","2")); out.clear();
            Message create=Message.of("TODO_CREATE","id-a","2","저녁 메뉴","지원","같이 정해요","지원","","");
            d.send(create); Message created=out.get(out.size()-1);
            check(created.type().equals("TODO_CREATED") && created.field(0).equals("2") && created.field(1).equals("id-a"),"데모 변경 알림 roomId/requestId 순서");
            d.send(create); check(out.get(out.size()-1).equals(created),"동일 requestId 재요청은 기존 성공 응답");
            d.send(Message.of("TODO_LIST","2")); check(out.get(out.size()-3).equals(Message.of("TODO_LIST_START","2","1")),"중복 생성 없이 한 항목 유지");
            d.send(Message.of("TODO_EDIT","id-a",created.field(2),"1","다른 요청","","",""));
            check(out.get(out.size()-1).type().equals("ERROR"),"같은 ID 다른 요청 거부");
            d.send(Message.of("TODO_CREATE"));
            check(out.get(out.size()-1).equals(Message.of("ERROR","INVALID_TODO","requestId가 필요해요.","")),"필수 필드 누락은 빈 requestId 오류");
            d.send(Message.of("ROOM_LEAVE")); d.send(Message.of("ROOM_JOIN","1")); d.send(create);
            check(out.get(out.size()-1).field(0).equals("NOT_IN_ROOM"),"다른 방에서 캐시된 성공 응답 재전송 금지");
            d.send(Message.of("ROOM_LEAVE")); d.send(Message.of("ROOM_JOIN","2")); d.send(Message.of("TODO_LIST","2"));
            check(out.get(out.size()-3).field(1).equals("1"),"데모 방 전원 퇴장 후 할 일 보존");
        } finally { d.close(); }
    }
    private static void demoUiChecks() throws Exception {
        ChatPanel p=edt(()->{Theme.install(); return new ChatPanel();});
        try {
            set(p,"nickname","김지원"); click(p,"demo"); await(()->size(p,"roomList")==4,"3주차 데모 연결");
            openRoom(p,0); await(()->enabled(p,"send"),"데모 방 입장"); click(p,"plannerToggle");
            await(()->size(p,"plannerList")==2 && enabled(p,"plannerRefresh"),"할 일·약속을 한 목록에 함께 조회");
            check(visible(p,"plannerListPage") && !visible(p,"plannerFormPage"),"처음에는 통합 목록만 표시");
            check(find(p,"plannerTodos")==null && find(p,"plannerSchedules")==null,"종류별 목록 탭 제거");
            check(visible(p,"plannerClose"),"목록 오른쪽 위 닫기 버튼 표시"); snapshot(p,"week3-unified-plans",960,680);
            check(ui(()->((JButton)find(p,"plannerFilterButton")).getText().equals("모든 날짜 ▾")) && !visible(p,"plannerFilterClear"),"기본 날짜 필터는 버튼 하나로 표시");
            click(p,"plannerClose"); check(visible(p,"transcript") && !visible(p,"plannerPanel"),"닫기 버튼으로 대화 복귀");
            click(p,"plannerToggle"); await(()->enabled(p,"plannerRefresh"),"닫은 뒤 통합 목록 다시 열기");
            String todoId=ui(()->planner(p).state().todos().get(0).id()); String scheduleId=ui(()->planner(p).state().schedules().get(0).id());
            selectPlan(p,"TODO",todoId); check(visible(p,"plannerFormPage") && !visible(p,"plannerListPage"),"항목 선택 때만 상세 화면 표시");
            check(!visible(p,"plannerSource"),"대화 원문은 기본 접힘"); click(p,"plannerSourceToggle"); check(visible(p,"plannerSource"),"원문 보기로 필요한 내용만 펼치기");
            click(p,"plannerBack"); check(visible(p,"plannerListPage") && size(p,"plannerList")==2,"상세에서 돌아오면 두 종류가 모두 표시"); selectPlan(p,"TODO",todoId);
            check(!enabled(p,"plannerSave") && enabled(p,"plannerComplete"),"타인 작성 할 일은 수정 불가·담당자는 완료 가능");
            click(p,"plannerComplete"); await(()->ui(()->planner(p).state().todo(todoId).done()),"담당자 완료 처리");
            check(ui(()->((JList<?>)find(p,"plannerList")).getModel().getElementAt(1).equals(new PlannerPanel.PlanKey("TODO",todoId))),"완료한 할 일은 통합 목록 아래로 정렬");
            selectPlan(p,"SCHEDULE",scheduleId);
            check(ui(()->((JLabel)find(p,"plannerFormTitle")).getText().equals("약속 상세")) && !visible(p,"plannerAssignee") && !visible(p,"plannerComplete"),"약속은 약속 상세·메모를 열고 할 일 버튼 숨김");
            snapshot(p,"week3-schedule-detail",960,680); click(p,"plannerBack"); click(p,"plannerNew");
            check(visible(p,"plannerCreatePage") && visible(p,"plannerAddTodo") && visible(p,"plannerAddSchedule"),"추가할 때만 할 일·약속 종류 선택");
            snapshot(p,"week3-plan-add",960,680); click(p,"plannerAddTodo");
            check(visible(p,"plannerSourceGuide"),"할 일 선택은 원문 메시지 선택 방법 안내"); click(p,"plannerPickMessage"); check(visible(p,"transcript"),"원문 선택을 위해 대화로 복귀");
            click(p,"plannerToggle"); await(()->enabled(p,"plannerRefresh"),"원문 안내 뒤 계획 다시 열기");
            check(visible(p,"plannerListPage") && !visible(p,"plannerSourceGuide"),"안내만 보고 닫으면 다시 열 때 목록부터 표시");
            click(p,"plannerNew"); click(p,"plannerAddSchedule"); set(p,"plannerTitle","주말에 같이 산책하기"); set(p,"plannerDate","2026-11-22"); set(p,"plannerTime","14:30"); set(p,"plannerMemo","공원 입구에서 만나요.\n비가 오면 카페로 가요.");
            click(p,"plannerClose"); check(visible(p,"transcript"),"약속 작성 중에도 바로 닫기 가능");
            click(p,"plannerToggle"); await(()->enabled(p,"plannerSave"),"작성하던 약속으로 다시 열기");
            check(visible(p,"plannerFormPage") && text(p,"plannerTitle").equals("주말에 같이 산책하기"),"닫았다 열어도 약속 초안 보존");
            click(p,"plannerBack"); check(visible(p,"plannerResume"),"통합 목록으로 돌아가도 초안 이어쓰기 제공");
            selectPlan(p,"TODO",todoId); check(visible(p,"plannerListPage") && text(p,"plannerTitle").equals("주말에 같이 산책하기"),"다른 종류 선택으로 작성 중인 약속을 덮어쓰지 않음");
            click(p,"plannerResume"); check(visible(p,"plannerFormPage") && text(p,"plannerMemo").contains("비가 오면"),"이어쓰기에서 메모까지 보존");
            edt(()->{planner(p).getActionMap().get("closePlanner").actionPerformed(new ActionEvent(p,0,"closePlanner"));return null;});
            check(visible(p,"transcript"),"Escape 닫기 동작으로 대화 복귀"); click(p,"plannerToggle"); await(()->enabled(p,"plannerSave"),"Escape 뒤 약속 초안 복원");
            snapshot(p,"week3-schedule-form",1080,720); click(p,"plannerSave"); await(()->size(p,"plannerList")==3 && enabled(p,"plannerNew"),"약속 등록 후 통합 목록 갱신");
            snapshot(p,"week3-schedules",960,680); check(visible(p,"plannerListPage") && !visible(p,"plannerFeedback"),"저장 후 목록 복귀·상시 성공 로그 숨김");
            String added=ui(()->planner(p).state().schedules().stream().filter(item->item.title().equals("주말에 같이 산책하기")).findFirst().orElseThrow().id());
            selectPlan(p,"SCHEDULE",added); set(p,"plannerTitle","주말 산책과 커피"); click(p,"plannerSave");
            await(()->ui(()->planner(p).state().schedule(added).title().equals("주말 산책과 커피")),"약속 수정");
            selectPlan(p,"SCHEDULE",added); click(p,"plannerDelete"); check(size(p,"plannerList")==3,"삭제 첫 클릭은 확인만"); click(p,"plannerDelete");
            await(()->size(p,"plannerList")==2,"약속 삭제 후 다른 계획 유지");
            click(p,"plannerClose"); set(p,"messageInput","내일 사진 정리 같이 해요"); click(p,"send"); await(()->enabled(p,"send"),"할 일 원문 채팅 전송");
            click(p,"plannerToggle"); await(()->enabled(p,"plannerRefresh"),"계획 화면 복귀");
            set(p,"plannerFilter","2026-11-22");
            edt(()->{planner(p).fromMessage("김지원","내일 사진 정리 같이 해요"); return null;}); await(()->enabled(p,"plannerSave"),"원문 사본으로 할 일 폼 생성");
            set(p,"plannerDate","2026-02-30"); click(p,"plannerSave"); check(text(p,"plannerFeedback").contains("존재"),"잘못된 날짜를 화면에서 안내");
            check(text(p,"plannerTitle").equals("내일 사진 정리 같이 해요"),"입력 오류 후 폼 보존");
            set(p,"plannerDate","2026-11-23"); snapshot(p,"week3-todo-form",960,680); click(p,"plannerSave");
            await(()->size(p,"plannerList")==3 && enabled(p,"plannerRefresh"),"할 일 생성 후 약속과 함께 표시");
            check(text(p,"plannerFilter").isEmpty() && visible(p,"plannerListPage"),"필터 밖 날짜에 할 일을 생성하면 전체 목록에서 바로 표시");
            String myTodo=ui(()->planner(p).state().todos().stream().filter(item->item.title().equals("내일 사진 정리 같이 해요")).findFirst().orElseThrow().id());
            selectPlan(p,"TODO",myTodo); snapshot(p,"week3-todos",1080,720); click(p,"plannerComplete"); await(()->ui(()->planner(p).state().todo(myTodo).done()),"작성자 완료 변경");
            selectPlan(p,"TODO",myTodo); click(p,"plannerComplete"); await(()->ui(()->!planner(p).state().todo(myTodo).done()),"완료 취소");
            leaveViaMenu(p); await(()->enabled(p,"refresh"),"계획 화면에서 퇴장"); openRoom(p,0); await(()->enabled(p,"send"),"데모 재입장"); click(p,"plannerToggle");
            await(()->size(p,"plannerList")==3 && enabled(p,"plannerRefresh"),"재입장 후 두 종류 계획 재조회");
        } finally { edt(()->{p.close(); return null;}); }
    }
    private static void tcpChecks() throws Exception {
        ChatPanel p=edt(()->new ChatPanel());
        try(TestPeer peer=new TestPeer()) {
            set(p,"nickname","지원"); set(p,"port",""+peer.port()); click(p,"connect"); peer.accept();
            check(peer.take().equals(Message.of("HELLO","지원")),"TCP HELLO");
            peer.send(Message.of("WELCOME","지원"),Message.of("ROOMS","r1","연동 확인","2","r2","다른 방","0"));
            await(()->size(p,"roomList")==2,"TCP 방 목록"); openRoom(p,0); check(peer.take().equals(Message.of("ROOM_JOIN","r1")),"TCP 입장 요청");
            peer.send(Message.of("ROOM_JOINED","r1","연동 확인"),Message.of("ROOM_USERS","r1","지원","정윤"));
            await(()->enabled(p,"send"),"TCP 입장 완료"); click(p,"plannerToggle"); check(peer.take().equals(Message.of("TODO_LIST","r1")),"requestId 없는 목록 요청");
            peer.send(Message.of("TODO_LIST_START","r1","1"),todo("TODO_ITEM","","","t1","1","처음 제목","지원","정윤","0"));
            edt(()->null); check(size(p,"plannerList")==0,"TCP 부분 목록 노출 없음");
            peer.send(Message.of("TODO_LIST_END","r1"));
            check(peer.take().equals(Message.of("SCHEDULE_LIST","r1","")),"할 일 END 이후 기존 규칙의 전체 일정 요청 전송");
            check(size(p,"plannerList")==1 && !enabled(p,"plannerNew"),"두 번째 목록을 기다리는 동안 편집 요청 차단");
            peer.send(Message.of("SCHEDULE_LIST_START","r1","1"),Message.of("SCHEDULE_ITEM","t1","1","2026-11-20","팀 약속","10:00","메모","지원"),Message.of("SCHEDULE_LIST_END","r1"));
            await(()->size(p,"plannerList")==2 && enabled(p,"plannerRefresh"),"종류별 ID가 같아도 두 항목을 따로 표시");
            selectPlan(p,"SCHEDULE","t1"); check(!enabled(p,"plannerComplete"),"같은 ID의 할 일이 있어도 약속 완료 동작은 비활성화"); set(p,"plannerTitle","변경된 약속"); click(p,"plannerSave"); Message scheduleEdit=peer.take();
            check(scheduleEdit.type().equals("SCHEDULE_EDIT") && scheduleEdit.field(1).equals("t1") && scheduleEdit.field(4).equals("변경된 약속"),"같은 ID의 약속 선택은 SCHEDULE_EDIT으로 전송");
            peer.send(Message.of("SCHEDULE_EDITED","r1",scheduleEdit.field(0),"t1","2","2026-11-20","변경된 약속","10:00","메모","지원"));
            await(()->enabled(p,"plannerRefresh"),"약속 수정 성공 처리");
            check(ui(()->planner(p).state().todo("t1").title().equals("처음 제목")),"약속 수정이 같은 ID의 할 일을 덮어쓰지 않음");
            selectPlan(p,"TODO","t1"); set(p,"plannerTitle","내가 작성 중인 제목"); click(p,"plannerSave"); Message request=peer.take();
            check(request.type().equals("TODO_EDIT") && request.fields().size()==7 && request.field(1).equals("t1") && request.field(2).equals("1"),"수정 필드 순서와 expectedRevision");
            UUID.fromString(request.field(0)); check(true,"프론트가 UUID requestId 발급");
            peer.send(Message.of("ERROR","INVALID_TODO","다른 요청 오류","unrelated"));
            edt(()->null); check(!enabled(p,"plannerSave"),"다른 requestId 오류는 대기를 해제하지 않음");
            peer.send(todo("TODO_EDITED","other",request.field(0),"t1","2","다른 방","지원","정윤","0"));
            edt(()->null); check(!enabled(p,"plannerSave"),"같은 requestId여도 다른 방 성공 응답 무시");
            peer.send(todo("TODO_EDITED","r1","other-id","t1","2","정윤 변경","지원","정윤","0"),Message.of("ERROR","CONFLICT","최신 내용을 확인해 주세요",request.field(0)));
            await(()->enabled(p,"plannerRefresh"),"CONFLICT로 해당 요청 대기 종료");
            check(text(p,"plannerTitle").equals("내가 작성 중인 제목") && !enabled(p,"plannerSave"),"충돌 시 입력 유지·자동 덮어쓰기 차단");
            snapshot(p,"week3-conflict",960,680);
            click(p,"plannerReview"); check(peer.take().type().equals("TODO_LIST"),"충돌 최신 확인에서 목록 재조회");
            peer.send(Message.of("TODO_LIST_START","r1","1"),todo("TODO_ITEM","","","t1","2","정윤 변경","지원","정윤","0"),Message.of("TODO_LIST_END","r1"));
            answerSchedules(peer);
            await(()->enabled(p,"plannerRefresh"),"충돌 재조회 완료"); click(p,"plannerReview"); check(enabled(p,"plannerSave"),"사용자 최신 확인 후 재저장 허용");
            check(text(p,"plannerLatest").contains("담당자: 정윤") && text(p,"plannerLatest").contains("정윤 변경"),"충돌 비교에 제목·담당자·마감·완료 정보 제공");
            click(p,"plannerSave"); Message retry=peer.take();
            check(!retry.field(0).equals(request.field(0)) && retry.field(2).equals("2") && retry.field(3).equals("내가 작성 중인 제목"),"새 requestId·최신 revision·보존한 입력으로 재저장");
            peer.send(todo("TODO_EDITED","r1",retry.field(0),"t1","3","내가 작성 중인 제목","지원","정윤","0"));
            await(()->enabled(p,"plannerRefresh") && text(p,"plannerTitle").isEmpty(),"정확한 성공 응답 후 폼 초기화");
            selectPlan(p,"TODO","t1"); set(p,"plannerTitle","오류 후 보존"); click(p,"plannerSave"); Message invalid=peer.take();
            peer.send(Message.of("ERROR","INVALID_TODO","입력 내용을 확인해 주세요. ".repeat(70)+"마지막 안내까지 확인했습니다.",invalid.field(0))); await(()->enabled(p,"plannerSave"),"3필드 입력 오류 후 연결 유지");
            check(text(p,"plannerTitle").equals("오류 후 보존"),"서버 입력 오류 후 초안 보존");
            snapshot(p,"week3-long-error",960,680);
            check(ui(()->{JScrollPane scroll=(JScrollPane)find(p,"plannerFeedbackScroll");return scroll.getHeight()<=140 && scroll.getVerticalScrollBar().getMaximum()>scroll.getVerticalScrollBar().getVisibleAmount();}),"긴 오류는 폼을 밀어내지 않고 세로 스크롤 제공");
            edt(()->{JScrollPane scroll=(JScrollPane)find(p,"plannerFeedbackScroll");scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());return null;});
            check(ui(()->{JScrollPane scroll=(JScrollPane)find(p,"plannerFeedbackScroll");return scroll.getViewport().getViewPosition().y+scroll.getViewport().getExtentSize().height>=scroll.getViewport().getViewSize().height-2;}),"스크롤로 오류 안내 마지막 줄까지 접근");
            snapshot(p,"week3-long-error-bottom",960,680);
            check(text(p,"plannerFeedback").contains("마지막 안내까지 확인했습니다."),"긴 안내 원문을 생략하지 않고 보존");
            peer.send(Message.of("ROOM_USERS","r1","지원"));
            await(()->size(p,"users")==1,"담당자 퇴장 수신");
            click(p,"plannerSave"); Message departed=peer.take();
            check(departed.field(4).equals("정윤"),"퇴장한 기존 담당자를 유지한 제목 수정 허용");
            peer.send(Message.of("ERROR","INVALID_TODO","검증 안내",departed.field(0))); await(()->enabled(p,"plannerSave"),"담당자 유지 요청 뒤 오류 안내");
            click(p,"plannerDiscard"); click(p,"plannerRefresh"); peer.take(); peer.send(Message.of("ERROR","NOT_IN_ROOM","목록 조회 거부"));
            await(()->enabled(p,"plannerRefresh"),"2필드 목록 오류 호환");
            click(p,"plannerRefresh"); peer.take(); peer.send(Message.of("TODO_LIST_START","r1","0"),Message.of("TODO_LIST_END","r1"));
            check(peer.take().type().equals("SCHEDULE_LIST"),"첫 목록 성공 뒤 두 번째 목록 조회");
            peer.send(Message.of("ERROR","NOT_IN_ROOM","약속 조회 실패"));
            await(()->enabled(p,"plannerRefresh") && text(p,"plannerFeedback").contains("모두 불러오지 못했어요"),"두 번째 목록 실패도 대기 해제와 부분 조회 오류 안내");
            click(p,"plannerClose"); set(p,"messageInput","계속 채팅"); click(p,"send"); check(peer.take().type().equals("CHAT_SEND"),"신규 오류 후 기존 채팅 사용 가능");
            peer.send(Message.of("ERROR","INVALID_TODO","늦은 오류",invalid.field(0))); edt(()->null); check(!enabled(p,"send"),"늦은 3필드 오류가 채팅 대기를 지우지 않음");
            peer.send(Message.of("CHAT_MESSAGE","r1","지원","계속 채팅")); await(()->enabled(p,"send"),"기존 채팅 성공 처리 유지");
            click(p,"plannerToggle"); peer.take();
            peer.send(Message.of("TODO_LIST_START","r1","1"),Message.of("TODO_ITEM","t2","1","자료 준비","지원","지원","2026-11-20","09:00","0","지원","자료 준비해요"),Message.of("TODO_LIST_END","r1"));
            check(peer.take().equals(Message.of("SCHEDULE_LIST","r1","")),"전체 일정 조회 필드 유지");
            peer.send(Message.of("SCHEDULE_LIST_START","r1","2"),Message.of("SCHEDULE_ITEM","s1","1","2026-11-20","회의","10:00","","지원"),Message.of("SCHEDULE_ITEM","s2","1","2026-11-21","산책","14:00","","지원"),Message.of("SCHEDULE_LIST_END","r1"));
            await(()->size(p,"plannerList")==3 && enabled(p,"plannerRefresh"),"통합 목록 새로고침 완료");
            set(p,"plannerFilter","2026-11-20");
            check(size(p,"plannerList")==2,"날짜 필터에 할 일 마감일과 약속 날짜를 함께 적용");
            check(visible(p,"plannerFilterClear") && ui(()->((JButton)find(p,"plannerFilterButton")).getText().contains("11월 20일")),"날짜 선택 후 같은 칩에 선택 날짜와 해제 버튼 표시");
            snapshot(p,"week3-compact-date-filter",960,680);
            set(p,"plannerFilter","2026-11-21");
            check(size(p,"plannerList")==1 && ui(()->planner(p).state().todos().size()==1 && planner(p).state().schedules().size()==2),"필터 변경으로 전체 원본 목록을 잃지 않음");
            check(peer.noRequest(),"날짜 필터는 프론트에서 적용하며 추가 통신 규칙 불필요");
            click(p,"plannerFilterClear"); check(size(p,"plannerList")==3 && !visible(p,"plannerFilterClear"),"필터 ×로 모든 날짜 복원 후 해제 버튼 숨김");
            set(p,"plannerFilter","2026-11-21");
            click(p,"plannerNew"); click(p,"plannerAddSchedule"); set(p,"plannerTitle","약속"); set(p,"plannerDate","2026-11-22"); set(p,"plannerTime","15:00"); set(p,"plannerMemo","첫 줄\n둘째 줄"); click(p,"plannerSave"); Message schedule=peer.take();
            check(schedule.type().equals("SCHEDULE_CREATE") && schedule.fields().size()==6 && schedule.field(5).equals("첫 줄\n둘째 줄"),"일정 등록 TCP 필드·줄바꿈 보존");
            peer.send(Message.of("SCHEDULE_CREATED","r1",schedule.field(0),"s3","1","2026-11-22","약속","15:00","첫 줄\n둘째 줄","지원"));
            await(()->enabled(p,"plannerNew") && size(p,"plannerList")==4,"필터 밖 날짜 약속 생성 후 전체 목록 표시");
            check(text(p,"plannerFilter").isEmpty() && selectedPlan(p).equals(new PlannerPanel.PlanKey("SCHEDULE","s3")) && visible(p,"plannerListPage"),"생성한 약속을 상세 진입 없이 목록에서 선택");
            snapshot(p,"week3-saved-plan",960,680);
            set(p,"plannerFilter","2026-11-22"); selectPlan(p,"SCHEDULE","s3"); set(p,"plannerTitle","약속 이름 수정"); click(p,"plannerSave"); Message sameDateEdit=peer.take();
            peer.send(Message.of("SCHEDULE_EDITED","r1",sameDateEdit.field(0),"s3","2","2026-11-22","약속 이름 수정","15:00","첫 줄\n둘째 줄","지원"));
            await(()->enabled(p,"plannerNew"),"같은 날짜 약속 수정 성공");
            check(text(p,"plannerFilter").equals("2026-11-22") && size(p,"plannerList")==1,"저장한 날짜가 필터에 맞으면 필터 유지");
            selectPlan(p,"SCHEDULE","s3"); set(p,"plannerDate","2026-11-23"); click(p,"plannerSave"); Message dateEdit=peer.take();
            check(dateEdit.type().equals("SCHEDULE_EDIT") && dateEdit.field(3).equals("2026-11-23"),"날짜 수정에도 기존 SCHEDULE_EDIT 필드 유지");
            peer.send(Message.of("SCHEDULE_EDITED","r1",dateEdit.field(0),"s3","3","2026-11-23","약속 이름 수정","15:00","첫 줄\n둘째 줄","지원"));
            await(()->enabled(p,"plannerNew") && size(p,"plannerList")==4,"약속 날짜 변경 후 필터 해제와 전체 목록 복원");
            check(text(p,"plannerFilter").isEmpty() && selectedPlan(p).equals(new PlannerPanel.PlanKey("SCHEDULE","s3")),"수정으로 필터 밖으로 이동해도 항목 선택 유지");
            leaveViaMenu(p); check(peer.take().type().equals("ROOM_LEAVE"),"계획 화면 퇴장 요청"); peer.send(Message.of("ROOM_LEFT")); await(()->enabled(p,"refresh"),"퇴장 응답 반영");
            peer.send(Message.of("SCHEDULE_CREATED","r1",schedule.field(0),"s3","1","2026-11-21","과거 알림","15:00","","지원"));
            edt(()->null); check(ui(()->planner(p).state().schedules().isEmpty()) && text(p,"plannerFilter").isEmpty(),"퇴장 후 이전 방 이벤트와 날짜 필터 초기화");
        } finally { edt(()->{p.close();return null;}); }
    }
    private static void quickCompleteChecks() throws Exception {
        ChatPanel p=edt(()->new ChatPanel());
        try(TestPeer peer=new TestPeer()) {
            set(p,"nickname","지원"); set(p,"port",""+peer.port()); click(p,"connect"); peer.accept(); peer.take();
            peer.send(Message.of("WELCOME","지원"),Message.of("ROOMS","r1","바로 완료 확인","2"));
            await(()->size(p,"roomList")==1,"목록 바로 완료용 접속"); openRoom(p,0); peer.take();
            peer.send(Message.of("ROOM_JOINED","r1","바로 완료 확인"),Message.of("ROOM_USERS","r1","지원","정윤"));
            await(()->enabled(p,"send"),"바로 완료용 방 입장"); click(p,"plannerToggle"); peer.take();
            peer.send(Message.of("TODO_LIST_START","r1","2"),todo("TODO_ITEM","","","q1","1","내 할 일","정윤","지원","0"),todo("TODO_ITEM","","","locked","1","다른 사람 할 일","정윤","정윤","0"),Message.of("TODO_LIST_END","r1"));
            answerSchedules(peer); await(()->enabled(p,"plannerNew"),"바로 완료용 목록 준비"); snapshot(p,"week3-quick-before",960,680);
            quickClick(p,"locked",1); check(peer.noRequest() && visible(p,"plannerListPage"),"권한 없는 원형 표시는 요청이나 상세 화면을 열지 않음");
            listKey(p,KeyEvent.VK_DOWN,false); check(visible(p,"plannerListPage") && selectedPlan(p).id().equals("locked"),"방향키는 목록 선택만 이동하고 상세를 열지 않음");
            listKey(p,KeyEvent.VK_DOWN,false); check(selectedPlan(p).id().equals("q1") && visible(p,"plannerListPage"),"아래 방향키로 다음 항목 탐색");
            listKey(p,KeyEvent.VK_UP,false); listKey(p,KeyEvent.VK_SPACE,false); listKey(p,KeyEvent.VK_SPACE,true);
            check(peer.noRequest(),"Space도 작성자·담당자 권한 검사");
            listKey(p,KeyEvent.VK_ENTER,false); check(visible(p,"plannerFormPage") && text(p,"plannerTitle").equals("다른 사람 할 일"),"Enter로 선택한 항목 상세 열기");
            click(p,"plannerBack"); check(visible(p,"plannerListPage") && selectedPlan(p).id().equals("locked"),"상세에서 돌아와도 목록 선택 유지");
            peer.send(todo("TODO_EDITED","r1","broadcast","locked","2","갱신된 제목","정윤","정윤","0"));
            await(()->ui(()->planner(p).state().todo("locked").revision()==2),"탐색 중 변경 알림 수신");
            check(selectedPlan(p).id().equals("locked") && visible(p,"plannerListPage"),"실시간 목록 갱신도 탐색 선택을 유지");
            click(p,"plannerNew"); click(p,"plannerAddSchedule"); set(p,"plannerTitle","작성 중인 약속"); click(p,"plannerBack");
            quickClick(p,"q1",1); check(peer.noRequest() && text(p,"plannerTitle").equals("작성 중인 약속"),"초안 작성 중 바로 완료는 초안을 건드리지 않음");
            click(p,"plannerResume"); click(p,"plannerDiscard"); quickClick(p,"q1",1); Message request=peer.take();
            check(request.type().equals("TODO_COMPLETE") && request.fields().size()==4 && request.field(1).equals("q1") && request.field(2).equals("1") && request.field(3).equals("1"),"목록 클릭도 기존 TODO_COMPLETE와 revision 필드로 전송");
            UUID.fromString(request.field(0)); check(true,"목록 완료 요청도 UUID 사용");
            check(visible(p,"plannerListPage") && !ui(()->planner(p).state().todo("q1").done()),"응답 전 완료 체크를 바꾸거나 상세를 열지 않음");
            quickClick(p,"q1",1); check(peer.noRequest(),"처리 중 연속 클릭은 추가 완료 요청을 보내지 않음");
            peer.send(Message.of("ERROR","CONFLICT","다른 요청 오류","unrelated")); edt(()->null);
            check(!enabled(p,"plannerRefresh"),"관계없는 오류는 목록 완료 대기를 해제하지 않음");
            peer.send(Message.of("TODO_COMPLETED","r1",request.field(0),"q1","2","1"));
            await(()->enabled(p,"plannerRefresh") && ui(()->planner(p).state().todo("q1").done()),"성공 응답 후에만 완료 반영");
            check(visible(p,"plannerListPage") && !visible(p,"plannerFormPage"),"완료 후에도 목록 유지");
            snapshot(p,"week3-quick-completed",960,680);
            quickClick(p,"q1",2); check(peer.noRequest(),"더블클릭의 두 번째 클릭은 완료 취소를 자동 전송하지 않음");
            quickClick(p,"q1",1); Message undo=peer.take();
            check(undo.field(2).equals("2") && undo.field(3).equals("0"),"완료 취소는 최신 revision과 0으로 요청");
            peer.send(Message.of("ERROR","CONFLICT","다른 사람이 변경했어요",undo.field(0)));
            await(()->enabled(p,"plannerRefresh"),"바로 완료 충돌 뒤 대기 해제");
            check(ui(()->planner(p).state().todo("q1").done()) && text(p,"plannerFeedback").contains("새로고침"),"충돌 후 기존 완료 상태 유지와 갱신 안내");
            quickClick(p,"q1",1); check(peer.noRequest(),"충돌 뒤 새로고침 전 같은 오래된 버전으로 재요청 금지");
            click(p,"plannerRefresh"); peer.take();
            peer.send(Message.of("TODO_LIST_START","r1","1"),todo("TODO_ITEM","","","q1","3","내 할 일","정윤","지원","1"),Message.of("TODO_LIST_END","r1"));
            answerSchedules(peer); await(()->enabled(p,"plannerRefresh"),"충돌 후 두 목록 재조회");
            quickClick(p,"q1",1); Message retry=peer.take(); check(retry.field(2).equals("3") && !retry.field(0).equals(undo.field(0)),"갱신 후 새 requestId·최신 revision으로 완료 취소");
            peer.send(Message.of("TODO_COMPLETED","r1",retry.field(0),"q1","4","0"));
            await(()->enabled(p,"plannerRefresh") && ui(()->!planner(p).state().todo("q1").done()),"목록에서 완료 취소 성공");
            check(selectedPlan(p).id().equals("q1"),"완료 정렬 변경 뒤 같은 항목 선택 유지");
            listKey(p,KeyEvent.VK_SPACE,false); Message keyboard=peer.take();
            check(keyboard.type().equals("TODO_COMPLETE") && keyboard.field(2).equals("4") && keyboard.field(3).equals("1"),"Space 완료도 기존 통신 규칙과 최신 버전 사용");
            check(enabled(p,"plannerList"),"요청 중에도 목록을 비활성화하지 않아 키보드 초점 유지");
            listKey(p,KeyEvent.VK_ENTER,false); check(visible(p,"plannerListPage"),"대기 중 Enter는 상세로 전환하지 않음");
            listKey(p,KeyEvent.VK_SPACE,false); check(peer.noRequest(),"Space를 누르고 있어도 처리 중 중복 요청 없음");
            peer.send(Message.of("TODO_COMPLETED","r1",keyboard.field(0),"q1","5","1"));
            await(()->enabled(p,"plannerRefresh"),"키보드 완료 성공");
            listKey(p,KeyEvent.VK_SPACE,false); check(peer.noRequest(),"응답 후에도 키를 떼기 전 자동 반복 요청 없음");
            listKey(p,KeyEvent.VK_SPACE,true); listKey(p,KeyEvent.VK_SPACE,false); listKey(p,KeyEvent.VK_SPACE,true); Message keyboardUndo=peer.take();
            check(keyboardUndo.field(2).equals("5") && keyboardUndo.field(3).equals("0"),"키를 뗐다 다시 누르면 완료 취소 가능");
            peer.send(Message.of("TODO_COMPLETED","r1",keyboardUndo.field(0),"q1","6","0"));
            await(()->enabled(p,"plannerRefresh") && visible(p,"plannerListPage"),"키보드 완료 취소 후 목록 유지");
        } finally { edt(()->{p.close();return null;}); }
    }
    private static PlannerPanel.PlanKey selectedPlan(ChatPanel p) {
        return ui(()->(PlannerPanel.PlanKey)((JList<?>)find(p,"plannerList")).getSelectedValue());
    }
    private static void listKey(ChatPanel p,int key,boolean released) throws Exception {
        edt(()->{JList<?> list=(JList<?>)find(p,"plannerList");Object binding=list.getInputMap().get(KeyStroke.getKeyStroke(key,0,released));
            Action action=list.getActionMap().get(binding); if(action==null)throw new AssertionError("키 바인딩 없음: "+key);
            action.actionPerformed(new ActionEvent(list,ActionEvent.ACTION_PERFORMED,binding.toString())); return null;});
    }
    private static void quickClick(ChatPanel p,String id,int clicks) throws Exception {
        edt(()->{JList<?> list=(JList<?>)find(p,"plannerList"); int index=-1;
            for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i).equals(new PlannerPanel.PlanKey("TODO",id)))index=i;
            if(index<0)throw new AssertionError("할 일 없음: "+id);
            Rectangle rect=list.getCellBounds(index,index); int x=rect.x+32,y=rect.y+47;
            for(int type:new int[]{MouseEvent.MOUSE_PRESSED,MouseEvent.MOUSE_RELEASED,MouseEvent.MOUSE_CLICKED})
                list.dispatchEvent(new MouseEvent(list,type,System.currentTimeMillis(),0,x,y,clicks,false,MouseEvent.BUTTON1));
            return null;
        });
    }
    private static void answerSchedules(TestPeer peer) throws IOException {
        check(peer.take().equals(Message.of("SCHEDULE_LIST","r1","")),"목록 재조회는 두 종류를 순차 요청");
        peer.send(Message.of("SCHEDULE_LIST_START","r1","0"),Message.of("SCHEDULE_LIST_END","r1"));
    }
    private static void selectPlan(ChatPanel p,String kind,String id) throws Exception {
        edt(()->{ @SuppressWarnings("unchecked") JList<PlannerPanel.PlanKey> list=(JList<PlannerPanel.PlanKey>)find(p,"plannerList");
            PlannerPanel.PlanKey key=new PlannerPanel.PlanKey(kind,id); list.setSelectedValue(key,true); list.getActionMap().get("openItem").actionPerformed(new ActionEvent(list,0,"openItem")); return null; });
    }
    private static Message todo(String type,String room,String request,String id,String rev,String title,String author,String who,String done) {
        List<String> f=new ArrayList<>(); if(!type.equals("TODO_ITEM")) f.addAll(List.of(room,request));
        f.addAll(List.of(id,rev,title,author,who,"","",done,"정윤","자료를 같이 확인해 주세요")); return new Message(type,f);
    }
    static class TestPeer implements AutoCloseable {
        final ServerSocket server; Socket socket; DataInputStream input; DataOutputStream output;
        TestPeer() throws IOException {server=new ServerSocket(0,10,InetAddress.getLoopbackAddress()); server.setSoTimeout(5000);}
        int port(){return server.getLocalPort();}
        void accept() throws IOException {socket=server.accept();socket.setSoTimeout(5000);input=new DataInputStream(socket.getInputStream()); output=new DataOutputStream(socket.getOutputStream());}
        Message take() throws IOException { return Message.read(input); }
        boolean noRequest() throws IOException { socket.setSoTimeout(200); try { take(); return false; } catch(SocketTimeoutException expected) {return true;} finally {socket.setSoTimeout(5000);} }
        void send(Message...messages) throws IOException {for(Message m:messages)m.write(output);}
        public void close() throws IOException {if(socket!=null)socket.close();server.close();}
    }
    private static PlannerPanel planner(ChatPanel p) {return (PlannerPanel)find(p,"plannerPanel");}
    static Component find(Container parent,String name) {for(Component c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container child){Component f=find(child,name);if(f!=null)return f;}}return null;}
    private static void layout(Container parent){parent.doLayout();for(Component c:parent.getComponents())if(c instanceof Container child)layout(child);}
    static void snapshot(ChatPanel p,String name,int width,int height) throws Exception {edt(()->{
        p.setSize(width,height);for(int i=0;i<3;i++)layout(p);BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();p.printAll(g);g.dispose();
        Files.createDirectories(Path.of("build/screenshots"));ImageIO.write(image,"png",Path.of("build/screenshots/"+name+".png").toFile());return null;
    });}
    static void set(ChatPanel p,String name,String text)throws Exception{edt(()->{Component target=find(p,name); if(target instanceof PlannerDateFilter f)f.setValue(text); else ((JTextComponent)target).setText(text);return null;});}
    static String text(ChatPanel p,String name){return ui(()->{Component target=find(p,name);return target instanceof PlannerDateFilter f?f.getValue():((JTextComponent)target).getText();});}
    static void leaveViaMenu(ChatPanel p) throws Exception {
        clickRoomMenu(p, "방 나가기");
    }
    static void clickRoomMenu(ChatPanel p, String label) throws Exception {
        edt(() -> {
            JButton menu = (JButton)find(p, "roomMenu");
            if (!menu.isEnabled()) throw new AssertionError("방 메뉴 비활성");
            JMenuItem item = null;
            for (Component entry : menu.getComponentPopupMenu().getComponents())
                if (entry instanceof JMenuItem action && label.equals(action.getText())) item = action;
            if (item == null || !item.isEnabled()) throw new AssertionError(label);
            item.doClick();
            return null;
        });
    }
    static void click(ChatPanel p,String name)throws Exception{edt(()->{((JButton)find(p,name)).doClick();return null;});}
    private static void select(ChatPanel p,String name,int index)throws Exception{edt(()->{((JList<?>)find(p,name)).setSelectedIndex(index);return null;});}
    static int size(ChatPanel p,String name){return ui(()->((JList<?>)find(p,name)).getModel().getSize());}
    static boolean enabled(ChatPanel p,String name){return ui(()->find(p,name).isEnabled());}
    static boolean visible(ChatPanel p,String name){return ui(()->{for(Component c=find(p,name);c!=null;c=c.getParent())if(!c.isVisible())return false;return true;});}
    static void openRoom(ChatPanel p,int index)throws Exception{select(p,"roomList",index);edt(()->{((JList<?>)find(p,"roomList")).getActionMap().get("openRoom").actionPerformed(new ActionEvent(p,0,"openRoom"));return null;});}
    static <T>T ui(Callable<T> task){try{return edt(task);}catch(Exception e){throw new RuntimeException(e);}}
    static <T>T edt(Callable<T> task)throws Exception{if(SwingUtilities.isEventDispatchThread())return task.call();FutureTask<T> f=new FutureTask<>(task);SwingUtilities.invokeAndWait(f);return f.get();}
    private static void await(BooleanSupplier condition,String name)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(15);check(condition.getAsBoolean(),name);}
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
}
