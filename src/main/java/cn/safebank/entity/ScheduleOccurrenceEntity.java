package cn.safebank.entity;
public class ScheduleOccurrenceEntity {
    private String scheduleId;
    private java.time.LocalDate dueDate;
    private String actionId;
    public String getScheduleId() {
        return scheduleId;
    }
    public void setScheduleId(String value) {
        scheduleId=value;
    }
    public java.time.LocalDate getDueDate() {
        return dueDate;
    }
    public void setDueDate(java.time.LocalDate value) {
        dueDate=value;
    }
    public String getActionId() {
        return actionId;
    }
    public void setActionId(String value) {
        actionId=value;
    }
}
