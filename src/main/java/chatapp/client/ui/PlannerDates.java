package chatapp.client.ui;

import java.time.*;

/** 화면에만 쓰는 날짜 표현. 서버에 보내는 날짜·시간 문자열은 바꾸지 않는다. */
public final class PlannerDates {
    private PlannerDates() { }
    public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Seoul")); }
    public static String day(String value,LocalDate today) {
        LocalDate date; try { date=LocalDate.parse(value); } catch(DateTimeException malformed) { return value; }
        if(date.equals(today)) return "오늘";
        if(date.equals(today.plusDays(1))) return "내일";
        if(date.equals(today.minusDays(1))) return "어제";
        return absolute(value,today);
    }
    public static String absolute(String value,LocalDate today) {
        LocalDate date; try { date=LocalDate.parse(value); } catch(DateTimeException malformed) { return value; }
        return (date.getYear()==today.getYear()?"":date.getYear()+"년 ")+date.getMonthValue()+"월 "+date.getDayOfMonth()+"일";
    }
    public static boolean past(String date,String time,LocalDateTime now) {
        if(date.isEmpty()) return false;
        try {
            LocalDate d=LocalDate.parse(date);
            return time.isEmpty()?d.isBefore(now.toLocalDate()):d.atTime(LocalTime.parse(time)).isBefore(now.withSecond(0).withNano(0));
        } catch(DateTimeException malformed) { return false; }
    }
    private static String time(String value) {
        LocalTime t; try { t=LocalTime.parse(value); } catch(DateTimeException malformed) { return value; }
        int hour=t.getHour()%12; if(hour==0)hour=12;
        return (t.getHour()<12?"오전 ":"오후 ")+hour+"시"+(t.getMinute()==0?"":" "+t.getMinute()+"분");
    }
    public static String todo(String date,String time,boolean done,LocalDateTime now) {
        if(date.isEmpty()) return "";
        String when=day(date,now.toLocalDate())+(time.isEmpty()?"":" "+time(time));
        if(!done && past(date,time,now)) return "마감 지남 · "+when;
        return when+"까지";
    }
    public static String schedule(String date,String time,LocalDateTime now) {
        return (past(date,time,now)?"지난 약속 · ":"")+day(date,now.toLocalDate())+(time.isEmpty()?" · 시간 미정":" "+time(time));
    }
}
