package cn.safebank.entity;
public class ScheduleEntity {
    private String id;
    private String userId;
    private String sourceId;
    private String recipientId;
    private Long amountMinor;
    private Integer dayOfMonth;
    private java.time.LocalDate firstDate;
    private java.time.LocalDate endDate;
    private String status;
    public String getId() {
        return id;
    }
    public void setId(String value) {
        id=value;
    }
    public String getUserId() {
        return userId;
    }
    public void setUserId(String value) {
        userId=value;
    }
    public String getSourceId() {
        return sourceId;
    }
    public void setSourceId(String value) {
        sourceId=value;
    }
    public String getRecipientId() {
        return recipientId;
    }
    public void setRecipientId(String value) {
        recipientId=value;
    }
    public Long getAmountMinor() {
        return amountMinor;
    }
    public void setAmountMinor(Long value) {
        amountMinor=value;
    }
    public Integer getDayOfMonth() {
        return dayOfMonth;
    }
    public void setDayOfMonth(Integer value) {
        dayOfMonth=value;
    }
    public java.time.LocalDate getFirstDate() {
        return firstDate;
    }
    public void setFirstDate(java.time.LocalDate value) {
        firstDate=value;
    }
    public java.time.LocalDate getEndDate() {
        return endDate;
    }
    public void setEndDate(java.time.LocalDate value) {
        endDate=value;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String value) {
        status=value;
    }
}
