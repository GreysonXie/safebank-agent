package cn.safebank.entity;
public class AgentContextSlotEntity {
    private String conversationId;
    public String getConversationId() { return conversationId; }
    public void setConversationId(String value) { conversationId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String slotName;
    public String getSlotName() { return slotName; }
    public void setSlotName(String value) { slotName=value; }
    private String valueJson;
    public String getValueJson() { return valueJson; }
    public void setValueJson(String value) { valueJson=value; }
    private String provenance;
    public String getProvenance() { return provenance; }
    public void setProvenance(String value) { provenance=value; }
    private Long contextVersion;
    public Long getContextVersion() { return contextVersion; }
    public void setContextVersion(Long value) { contextVersion=value; }
    private String sourceTurnId;
    public String getSourceTurnId() { return sourceTurnId; }
    public void setSourceTurnId(String value) { sourceTurnId=value; }
    private String evidenceId;
    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String value) { evidenceId=value; }
    private java.time.OffsetDateTime updatedAt;
    public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.OffsetDateTime value) { updatedAt=value; }
}
