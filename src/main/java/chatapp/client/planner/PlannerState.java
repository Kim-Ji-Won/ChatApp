package chatapp.client.planner;

import chatapp.common.Message;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** EDT 소유 상태. 목록은 END까지 임시 보관하고, 실시간 알림은 방과 버전을 확인한다. */
public final class PlannerState {
    public record Todo(String id, long revision, String title, String author, String assignee,
                       String date, String time, boolean done, String sourceSender, String sourceText) {
        public List<String> fields() { return List.of(id,""+revision,title,author,assignee,date,time,done?"1":"0",sourceSender,sourceText); }
        public Todo completed(long rev, boolean value) { return new Todo(id,rev,title,author,assignee,date,time,value,sourceSender,sourceText); }
    }
    public record Schedule(String id, long revision, String date, String title, String time, String memo, String author) {
        public List<String> fields() { return List.of(id,""+revision,date,title,time,memo,author); }
    }
    private String room;
    private final Map<String,Todo> todos = new LinkedHashMap<>();
    private final Map<String,Schedule> schedules = new LinkedHashMap<>();
    private final Map<String,Long> todoVersions = new HashMap<>(), scheduleVersions = new HashMap<>();
    private final Set<String> deletedTodos = new HashSet<>(), deletedSchedules = new HashSet<>();
    private String listing, queryDate="", scheduleFilter="";
    private int expected=-1;
    private final Map<String,Todo> todoBuffer = new LinkedHashMap<>();
    private final Map<String,Schedule> scheduleBuffer = new LinkedHashMap<>();

    public void enter(String id) {
        room=id; todos.clear(); schedules.clear(); todoVersions.clear(); scheduleVersions.clear();
        deletedTodos.clear(); deletedSchedules.clear(); scheduleFilter=""; cancelList();
    }
    public String room() { return room; }
    public List<Todo> todos() { return List.copyOf(todos.values()); }
    public List<Schedule> schedules() { return schedules.values().stream()
            .sorted(Comparator.comparing(Schedule::date).thenComparing(Schedule::time).thenComparing(Schedule::id)).toList(); }
    public Todo todo(String id) { return todos.get(id); }
    public Schedule schedule(String id) { return schedules.get(id); }
    public void expectList(String kind, String date) {
        if(listing!=null) throw new IllegalStateException("목록 요청이 진행 중입니다.");
        listing=kind; queryDate=date; expected=-1; todoBuffer.clear(); scheduleBuffer.clear();
    }
    public void cancelList() { listing=null; expected=-1; todoBuffer.clear(); scheduleBuffer.clear(); }
    public static boolean supports(String type) { return type.startsWith("TODO_") || type.startsWith("SCHEDULE_"); }

    /** 목록 종료면 true. 변경 알림은 요청 완료와 별개로 상태에 반영한다. */
    public boolean accept(Message m) throws IOException {
        boolean todo=m.type().startsWith("TODO_");
        String kind=todo?"TODO":"SCHEDULE", action=m.type().substring(kind.length()+1);
        if(action.equals("LIST_START")) {
            m.requireFields(2);
            if(!Objects.equals(room,m.field(0)) || !kind.equals(listing)) return false;
            if(expected!=-1) throw new IOException("목록 시작 중복");
            expected=integer(m.field(1),0); return false;
        }
        if(action.equals("ITEM")) {
            m.requireFields(todo?10:7);
            if(!kind.equals(listing) || expected<0) return false;
            if(todo) {
                Todo item=parseTodo(m.fields());
                if(todoBuffer.putIfAbsent(item.id(),item)!=null) throw new IOException("중복 할 일 ID");
            } else {
                Schedule item=parseSchedule(m.fields());
                if(!queryDate.isEmpty() && !queryDate.equals(item.date())) throw new IOException("일정 조회 날짜 불일치");
                if(scheduleBuffer.putIfAbsent(item.id(),item)!=null) throw new IOException("중복 일정 ID");
            }
            if((todo?todoBuffer.size():scheduleBuffer.size())>expected) throw new IOException("목록 개수 초과");
            return false;
        }
        if(action.equals("LIST_END")) {
            m.requireFields(1);
            if(!Objects.equals(room,m.field(0)) || !kind.equals(listing)) return false;
            if(expected<0 || (todo?todoBuffer.size():scheduleBuffer.size())!=expected) throw new IOException("목록 개수 불일치");
            if(todo) {
                todos.clear();
                for(Todo t:todoBuffer.values()) { todos.put(t.id(),t); todoVersions.put(t.id(),t.revision()); deletedTodos.remove(t.id()); }
            } else {
                schedules.clear(); scheduleFilter=queryDate;
                for(Schedule s:scheduleBuffer.values()) { schedules.put(s.id(),s); scheduleVersions.put(s.id(),s.revision()); deletedSchedules.remove(s.id()); }
            }
            cancelList(); return true;
        }
        switch(action) {
            case "CREATED", "EDITED" -> m.requireFields(todo?12:9);
            case "COMPLETED" -> { if(!todo) throw new IOException("미지원 일정 완료 이벤트"); m.requireFields(5); }
            case "DELETED" -> m.requireFields(3);
            default -> throw new IOException("미지원 계획 메시지: "+m.type());
        }
        if(room==null || !room.equals(m.field(0))) return false;
        if(kind.equals(listing) && expected>=0) throw new IOException("목록 중간 변경 알림");
        if(action.equals("CREATED") || action.equals("EDITED")) {
            if(todo) {
                Todo t=parseTodo(m.fields().subList(2,12));
                if(!deletedTodos.contains(t.id()) && t.revision()>todoVersions.getOrDefault(t.id(),0L)) {
                    todos.put(t.id(),t); todoVersions.put(t.id(),t.revision());
                }
            } else {
                Schedule s=parseSchedule(m.fields().subList(2,9));
                if(!deletedSchedules.contains(s.id()) && s.revision()>scheduleVersions.getOrDefault(s.id(),0L)) {
                    scheduleVersions.put(s.id(),s.revision());
                    if(scheduleFilter.isEmpty() || scheduleFilter.equals(s.date())) schedules.put(s.id(),s);
                    else schedules.remove(s.id());
                }
            }
        } else if(action.equals("COMPLETED")) {
            long rev=revision(m.field(3)); boolean done=done(m.field(4));
            Todo old=todos.get(m.field(2));
            String id=m.field(2);
            if(!deletedTodos.contains(id) && rev>todoVersions.getOrDefault(id,0L)) {
                if(old!=null) todos.put(id,old.completed(rev,done));
                todoVersions.put(id,rev);
            }
        } else {
            String id=m.field(2);
            if(todo) { todos.remove(id); deletedTodos.add(id); }
            else { schedules.remove(id); deletedSchedules.add(id); }
        }
        return false;
    }
    public static Todo parseTodo(List<String> f) throws IOException {
        if(f.size()!=10 || f.get(0).isEmpty() || f.get(3).isEmpty()) throw new IOException("할 일 필드 오류");
        return new Todo(f.get(0),revision(f.get(1)),f.get(2),f.get(3),f.get(4),f.get(5),f.get(6),done(f.get(7)),f.get(8),f.get(9));
    }
    public static Schedule parseSchedule(List<String> f) throws IOException {
        if(f.size()!=7 || f.get(0).isEmpty() || f.get(6).isEmpty()) throw new IOException("일정 필드 오류");
        return new Schedule(f.get(0),revision(f.get(1)),f.get(2),f.get(3),f.get(4),f.get(5),f.get(6));
    }
    private static int integer(String s,int min) throws IOException {
        try { int n=Integer.parseInt(s); if(n<min) throw new NumberFormatException(); return n; }
        catch(NumberFormatException e) { throw new IOException("목록 개수 오류",e); }
    }
    private static long revision(String s) throws IOException {
        try { long n=Long.parseLong(s); if(n<1) throw new NumberFormatException(); return n; }
        catch(NumberFormatException e) { throw new IOException("revision 오류",e); }
    }
    private static boolean done(String s) throws IOException {
        if(!s.equals("0") && !s.equals("1")) throw new IOException("완료 값 오류"); return s.equals("1");
    }
    public static String validate(String title,String date,String time,String memo,boolean schedule) {
        if(title.strip().isEmpty() || title.strip().length()>80 || controls(title,false)) return "제목은 줄바꿈 없이 1~80자로 입력해 주세요.";
        if(schedule && date.isEmpty()) return "일정 날짜를 입력해 주세요. 예: 2026-11-19";
        if(!date.isEmpty()) try {
            if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("형식");
            LocalDate.parse(date);
        } catch(DateTimeException e) { return "실제로 존재하는 날짜를 YYYY-MM-DD 형식으로 입력해 주세요."; }
        if(!time.isEmpty()) {
            if(date.isEmpty()) return "시간을 입력하려면 날짜도 입력해 주세요.";
            try { if(!time.matches("[0-9]{2}:[0-9]{2}")) throw new DateTimeException("형식"); LocalTime.parse(time); }
            catch(DateTimeException e) { return "시간은 00:00~23:59 범위의 HH:mm 형식으로 입력해 주세요."; }
        }
        if(memo.length()>500 || controls(memo,true)) return "메모는 줄바꿈을 포함해 500자 이내로 입력해 주세요. 탭 등 제어문자는 사용할 수 없어요.";
        return null;
    }
    public static boolean controls(String value,boolean multiline) {
        return value.chars().anyMatch(c -> Character.isISOControl(c) && !(multiline && c=='\n'));
    }
}
