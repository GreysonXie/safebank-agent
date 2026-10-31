package cn.safebank.common;
import cn.safebank.entity.ScheduleEntity;
import java.time.LocalDate;
public final class ScheduleRules {
    private ScheduleRules() {}
    public static boolean isDue(LocalDate date,int day) {return date.getDayOfMonth()==Math.min(day,date.lengthOfMonth());}
    public static String snapshot(ScheduleEntity s) {
        return String.join("|",s.getId(),s.getSourceId(),s.getRecipientId(),s.getAmountMinor().toString(),
                s.getDayOfMonth().toString(),s.getFirstDate().toString(),s.getEndDate().toString(),s.getStatus());
    }
}
