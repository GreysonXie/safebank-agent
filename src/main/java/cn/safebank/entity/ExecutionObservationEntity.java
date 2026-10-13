package cn.safebank.entity;
public class ExecutionObservationEntity {
    private String actionId;
    private String userId;
    private String state;
    private String receiptId;
    private String errorCode;
    private java.time.OffsetDateTime observedAt;
    public String getActionId() {
        return actionId;
    }
    public void setActionId(String value) {
        actionId=value;
    }
    public String getUserId() {
        return userId;
    }
    public void setUserId(String value) {
        userId=value;
    }
    public String getState() {
        return state;
    }
    public void setState(String value) {
        state=value;
    }
    public String getReceiptId() {
        return receiptId;
    }
    public void setReceiptId(String value) {
        receiptId=value;
    }
    public String getErrorCode() {
        return errorCode;
    }
    public void setErrorCode(String value) {
        errorCode=value;
    }
    public java.time.OffsetDateTime getObservedAt() {
        return observedAt;
    }
    public void setObservedAt(java.time.OffsetDateTime value) {
        observedAt=value;
    }
}
