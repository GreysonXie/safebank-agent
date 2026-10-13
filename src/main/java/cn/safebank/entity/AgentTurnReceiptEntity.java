package cn.safebank.entity;
public class AgentTurnReceiptEntity {
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id=value; }
    private String conversationId;
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { conversationId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String requestId;
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId=value; }
    private String requestHash;
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash=value; }
    private Long inputVersion;
    public Long getInputVersion() { return inputVersion; }
    public void setInputVersion(Long value) { inputVersion=value; }
    private Long outputVersion;
    public Long getOutputVersion() { return outputVersion; }
    public void setOutputVersion(Long value) { outputVersion=value; }
    private String state;
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    private String toolIntentsJson;
    public String getToolIntentsJson() { return toolIntentsJson; }
    public void setToolIntentsJson(String value) { toolIntentsJson=value; }
    private String bankReferencesJson;
    public String getBankReferencesJson() { return bankReferencesJson; }
    public void setBankReferencesJson(String value) { bankReferencesJson=value; }
    private String responseJson;
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String value) { responseJson=value; }
    private String errorCode;
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode=value; }
    private java.time.OffsetDateTime createdAt;
    public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.OffsetDateTime value) { createdAt=value; }
    private java.time.OffsetDateTime completedAt;
    public java.time.OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(java.time.OffsetDateTime value) { completedAt=value; }
}
