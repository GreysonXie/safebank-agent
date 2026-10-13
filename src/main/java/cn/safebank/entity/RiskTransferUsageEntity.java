package cn.safebank.entity;
public class RiskTransferUsageEntity {
    private String actionId;
    public String getActionId() { return actionId; }
    public void setActionId(String value) { actionId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String authorizationId;
    public String getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(String value) { authorizationId=value; }
    private String provenance;
    public String getProvenance() { return provenance; }
    public void setProvenance(String value) { provenance=value; }
    private java.time.LocalDate businessDate;
    public java.time.LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(java.time.LocalDate value) { businessDate=value; }
    private Long amountMinor;
    public Long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(Long value) { amountMinor=value; }
    private String state;
    public String getState() { return state; }
    public void setState(String value) { state=value; }
    private java.time.OffsetDateTime reservedUntil;
    public java.time.OffsetDateTime getReservedUntil() { return reservedUntil; }
    public void setReservedUntil(java.time.OffsetDateTime value) { reservedUntil=value; }
    private java.time.OffsetDateTime submittedAt;
    public java.time.OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(java.time.OffsetDateTime value) { submittedAt=value; }
    private java.time.OffsetDateTime resolvedAt;
    public java.time.OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(java.time.OffsetDateTime value) { resolvedAt=value; }
    private java.time.OffsetDateTime updatedAt;
    public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.OffsetDateTime value) { updatedAt=value; }
}
