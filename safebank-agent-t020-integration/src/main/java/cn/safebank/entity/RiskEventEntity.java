package cn.safebank.entity;
public class RiskEventEntity {
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id=value; }
    private String requestId;
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String actionId;
    public String getActionId() { return actionId; }
    public void setActionId(String value) { actionId=value; }
    private String decisionId;
    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String value) { decisionId=value; }
    private String eventCode;
    public String getEventCode() { return eventCode; }
    public void setEventCode(String value) { eventCode=value; }
    private String reasonCode;
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode=value; }
    private String channel;
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel=value; }
    private String policyVersion;
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion=value; }
    private java.time.OffsetDateTime recordedAt;
    public java.time.OffsetDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(java.time.OffsetDateTime value) { recordedAt=value; }
}
