package cn.safebank.entity;
public class RiskDecisionEntity {
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id=value; }
    private String actionId;
    public String getActionId() { return actionId; }
    public void setActionId(String value) { actionId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private Long revision;
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision=value; }
    private String policyVersion;
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion=value; }
    private String riskLevel;
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String value) { riskLevel=value; }
    private String requiredAssurance;
    public String getRequiredAssurance() { return requiredAssurance; }
    public void setRequiredAssurance(String value) { requiredAssurance=value; }
    private String reasonCode;
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode=value; }
    private java.time.LocalDate businessDate;
    public java.time.LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(java.time.LocalDate value) { businessDate=value; }
    private Long amountMinor;
    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long value) { amountMinor=value; }
    private Long effectiveMinor;
    public Long getEffectiveMinor() { return effectiveMinor; }
    public void setEffectiveMinor(Long value) { effectiveMinor=value; }
    private Long projectedMinor;
    public Long getProjectedMinor() { return projectedMinor; }
    public void setProjectedMinor(Long value) { projectedMinor=value; }
    private String payloadHash;
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { payloadHash=value; }
    private java.time.OffsetDateTime createdAt;
    public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.OffsetDateTime value) { createdAt=value; }
    private java.time.OffsetDateTime validUntil;
    public java.time.OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(java.time.OffsetDateTime value) { validUntil=value; }
}
