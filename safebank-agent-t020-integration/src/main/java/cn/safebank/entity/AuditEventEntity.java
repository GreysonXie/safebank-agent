package cn.safebank.entity;
public class AuditEventEntity {
    private String id;
    private String userId;
    private String actionId;
    private String event;
    private java.time.OffsetDateTime recordedAt;
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
    public String getActionId() {
        return actionId;
    }
    public void setActionId(String value) {
        actionId=value;
    }
    public String getEvent() {
        return event;
    }
    public void setEvent(String value) {
        event=value;
    }
    public java.time.OffsetDateTime getRecordedAt() {
        return recordedAt;
    }
    public void setRecordedAt(java.time.OffsetDateTime value) {
        recordedAt=value;
    }
}
