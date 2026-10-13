package cn.safebank.entity;
public class AgentContextEvidenceEntity {
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id=value; }
    private String conversationId;
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { conversationId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String evidenceKey;
    public String getEvidenceKey() { return evidenceKey; }
    public void setEvidenceKey(String value) { evidenceKey=value; }
    private String toolName;
    public String getToolName() { return toolName; }
    public void setToolName(String value) { toolName=value; }
    private String objectId;
    public String getObjectId() { return objectId; }
    public void setObjectId(String value) { objectId=value; }
    private String snapshotJson;
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String value) { snapshotJson=value; }
    private String contentHash;
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { contentHash=value; }
    private String sourceTurnId;
    public String getSourceTurnId() { return sourceTurnId; }
    public void setSourceTurnId(String value) { sourceTurnId=value; }
    private java.time.OffsetDateTime observedAt;
    public java.time.OffsetDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(java.time.OffsetDateTime value) { observedAt=value; }
    private java.time.OffsetDateTime validUntil;
    public java.time.OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(java.time.OffsetDateTime value) { validUntil=value; }
}
