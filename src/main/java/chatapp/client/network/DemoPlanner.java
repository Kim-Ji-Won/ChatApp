package chatapp.client.network;

import chatapp.client.planner.PlannerState;
import chatapp.client.planner.PlannerState.*;
import chatapp.common.Message;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** 화면 체험 전용 메모리 응답기. 실제 팀원 서버의 구현이나 검증을 대신하지 않는다. */
final class DemoPlanner {
    private record Reply(String room,Message request,Message response) { }
    private final Map<String,Map<String,Todo>> todos=new HashMap<>();
    private final Map<String,Map<String,Schedule>> schedules=new HashMap<>();
    private final Map<String,Reply> successes=new HashMap<>();
    private int sequence;
    void seed(String self,String peer) {
        String today=LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        todos.computeIfAbsent("1",k->new LinkedHashMap<>()).put("demo-todo-seed",
                new Todo("demo-todo-seed",1,"저녁 메뉴 같이 정하기",peer,self,today,"",false,peer,"오늘 저녁 메뉴 같이 정해 볼까요?"));
        schedules.computeIfAbsent("1",k->new LinkedHashMap<>()).put("demo-schedule-seed",
                new Schedule("demo-schedule-seed",1,today,"우리들의 저녁 약속","19:00","메뉴는 채팅에서 함께 골라요.",self));
    }
    void send(Message m,String room,String self,List<String> members,Consumer<Message> emit) {
        boolean todo=m.type().startsWith("TODO_"); String kind=todo?"TODO":"SCHEDULE";
        boolean query=m.type().equals(kind+"_LIST");
        String requestId=query || m.fields().isEmpty()?"":m.field(0);
        try {
            if(room==null) { error(emit,"NOT_IN_ROOM","먼저 방에 입장해 주세요.",requestId,query); return; }
            Map<String,Todo> ts=todos.computeIfAbsent(room,k->new LinkedHashMap<>());
            Map<String,Schedule> ss=schedules.computeIfAbsent(room,k->new LinkedHashMap<>());
            if(query) {
                m.requireFields(todo?1:2);
                if(!room.equals(m.field(0))) { error(emit,"NOT_IN_ROOM","현재 방만 조회할 수 있어요.","",true); return; }
                String date=todo?"":m.field(1), invalid=PlannerState.validate("조회",date,"","",false);
                if(invalid!=null) throw new IOException(invalid);
                List<List<String>> rows=todo?ts.values().stream().map(Todo::fields).toList():ss.values().stream()
                        .filter(s->date.isEmpty() || date.equals(s.date())).map(Schedule::fields).toList();
                emit.accept(Message.of(kind+"_LIST_START",room,""+rows.size()));
                for(List<String> fields:rows) emit.accept(new Message(kind+"_ITEM",fields));
                emit.accept(Message.of(kind+"_LIST_END",room)); return;
            }
            if(requestId.isEmpty()) throw new IOException("requestId가 필요해요.");
            Reply previous=successes.get(requestId);
            if(previous!=null) {
                if(!previous.room().equals(room)) error(emit,"NOT_IN_ROOM","이 요청을 보낸 방에 있지 않아요.",requestId,false);
                else if(!previous.request().equals(m)) error(emit,"INVALID_"+kind,"같은 requestId로 다른 내용을 보낼 수 없어요.",requestId,false);
                else emit.accept(previous.response());
                return;
            }
            String action=m.type().substring(kind.length()+1), entity=null;
            Todo oldTodo=null; Schedule oldSchedule=null;
            if(action.equals("CREATE")) {
                m.requireFields(todo?8:6);
                if(!room.equals(m.field(1))) { error(emit,"NOT_IN_ROOM","현재 방에만 등록할 수 있어요.",requestId,false); return; }
            } else {
                m.requireFields(action.equals("EDIT")?(todo?7:7):action.equals("COMPLETE")?4:3);
                entity=m.field(1); oldTodo=ts.get(entity); oldSchedule=ss.get(entity);
                if(todo?oldTodo==null:oldSchedule==null) { error(emit,kind+"_NOT_FOUND","항목을 찾을 수 없어요.",requestId,false); return; }
                String author=todo?oldTodo.author():oldSchedule.author();
                if(!self.equals(author) && !(todo && action.equals("COMPLETE") && self.equals(oldTodo.assignee()))) {
                    error(emit,"FORBIDDEN","이 항목을 변경할 권한이 없어요.",requestId,false); return;
                }
                long revision=Long.parseLong(m.field(2));
                if(revision!=(todo?oldTodo.revision():oldSchedule.revision())) { error(emit,"CONFLICT","다른 변경이 있어요. 최신 내용을 확인해 주세요.",requestId,false); return; }
            }
            Message response;
            if(action.equals("CREATE") || action.equals("EDIT")) {
                boolean create=action.equals("CREATE");
                if(todo) {
                    String title=m.field(create?2:3).strip(), who=m.field(create?5:4), date=m.field(create?6:5), time=m.field(create?7:6);
                    String invalid=PlannerState.validate(title,date,time,"",false); if(invalid!=null) throw new IOException(invalid);
                    if(!who.isEmpty() && !members.contains(who) && (oldTodo==null || !oldTodo.assignee().equals(who))) throw new IOException("현재 방의 담당자를 선택해 주세요.");
                    String from=create?m.field(3):oldTodo.sourceSender(), text=create?m.field(4):oldTodo.sourceText();
                    if(from.isBlank() || text.isBlank() || text.length()>1000 || PlannerState.controls(text,true)) throw new IOException("원문 사본을 확인해 주세요.");
                    Todo next=new Todo(create?"demo-todo-"+(++sequence):entity,create?1:oldTodo.revision()+1,title,
                            create?self:oldTodo.author(),who,date,time,!create && oldTodo.done(),from,text);
                    ts.put(next.id(),next); response=event(kind+(create?"_CREATED":"_EDITED"),room,requestId,next.fields());
                } else {
                    String date=m.field(create?2:3), title=m.field(create?3:4).strip(), time=m.field(create?4:5), memo=m.field(create?5:6);
                    String invalid=PlannerState.validate(title,date,time,memo,true); if(invalid!=null) throw new IOException(invalid);
                    Schedule next=new Schedule(create?"demo-schedule-"+(++sequence):entity,create?1:oldSchedule.revision()+1,date,title,time,memo,create?self:oldSchedule.author());
                    ss.put(next.id(),next); response=event(kind+(create?"_CREATED":"_EDITED"),room,requestId,next.fields());
                }
            } else if(action.equals("COMPLETE") && todo) {
                if(!List.of("0","1").contains(m.field(3))) throw new IOException("완료 값 오류");
                Todo next=oldTodo.completed(oldTodo.revision()+1,m.field(3).equals("1")); ts.put(entity,next);
                response=Message.of("TODO_COMPLETED",room,requestId,entity,""+next.revision(),next.done()?"1":"0");
            } else if(action.equals("DELETE")) {
                if(todo) ts.remove(entity); else ss.remove(entity);
                response=Message.of(kind+"_DELETED",room,requestId,entity);
            } else throw new IOException("미지원 요청");
            successes.put(requestId,new Reply(room,m,response)); emit.accept(response);
        } catch(IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            error(emit,"INVALID_"+kind,e.getMessage()==null?"입력을 확인해 주세요.":e.getMessage(),requestId,query);
        }
    }
    private static Message event(String type,String room,String requestId,List<String> item) {
        List<String> fields=new ArrayList<>(List.of(room,requestId)); fields.addAll(item); return new Message(type,fields);
    }
    private static void error(Consumer<Message> out,String code,String description,String id,boolean query) {
        out.accept(query?Message.of("ERROR",code,description):Message.of("ERROR",code,description,id));
    }
}
