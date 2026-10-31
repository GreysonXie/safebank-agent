package cn.safebank.entity;
public class AgentConversationEntity {
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private Long version;
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version=value; }
    private String intent;
    public String getIntent() { return intent; }
    public void setIntent(String value) { intent=value; }
    private String state;
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    private String proposalJson;
    public String getProposalJson() { return proposalJson; }
    public void setProposalJson(String value) { proposalJson=value; }
    private String displayHistoryJson;
    public String getDisplayHistoryJson() { return displayHistoryJson; }
    public void setDisplayHistoryJson(String value) { displayHistoryJson=value; }
    private java.time.OffsetDateTime createdAt;
    public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.OffsetDateTime value) { createdAt=value; }
    private java.time.OffsetDateTime updatedAt;
    public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.OffsetDateTime value) { updatedAt=value; }
    private java.time.OffsetDateTime expiresAt;
    public java.time.OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.OffsetDateTime value) { expiresAt=value; }
}
