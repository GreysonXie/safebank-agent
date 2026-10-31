package cn.safebank.entity;
public class RiskUserGuardEntity {
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private Integer failedVerifications;
    public Integer getFailedVerifications() { return failedVerifications; }
    public void setFailedVerifications(Integer value) { failedVerifications=value; }
    private java.time.OffsetDateTime failureWindowStartedAt;
    public java.time.OffsetDateTime getFailureWindowStartedAt() { return failureWindowStartedAt; }
    public void setFailureWindowStartedAt(java.time.OffsetDateTime value) { failureWindowStartedAt=value; }
    private java.time.OffsetDateTime lockedUntil;
    public java.time.OffsetDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(java.time.OffsetDateTime value) { lockedUntil=value; }
    private java.time.OffsetDateTime lastChallengeAt;
    public java.time.OffsetDateTime getLastChallengeAt() { return lastChallengeAt; }
    public void setLastChallengeAt(java.time.OffsetDateTime value) { lastChallengeAt=value; }
    private Boolean reconciliationRequired;
    public Boolean getReconciliationRequired() { return reconciliationRequired; }
    public void setReconciliationRequired(Boolean value) { reconciliationRequired=value; }
    private Long revision;
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision=value; }
    private java.time.OffsetDateTime updatedAt;
    public java.time.OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.OffsetDateTime value) { updatedAt=value; }
}
