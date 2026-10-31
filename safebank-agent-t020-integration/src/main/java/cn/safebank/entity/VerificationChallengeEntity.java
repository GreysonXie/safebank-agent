package cn.safebank.entity;
public class VerificationChallengeEntity {
    private String id;
    public String getId() { return id; }
    public void setId(String value) { id=value; }
    private String actionId;
    public String getActionId() { return actionId; }
    public void setActionId(String value) { actionId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String decisionId;
    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String value) { decisionId=value; }
    private String payloadHash;
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { payloadHash=value; }
    private String method;
    public String getMethod() { return method; }
    public void setMethod(String value) { method=value; }
    private String providerReference;
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String value) { providerReference=value; }
    private String codeDigest;
    public String getCodeDigest() { return codeDigest; }
    public void setCodeDigest(String value) { codeDigest=value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    private Integer failedAttempts;
    public Integer getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(Integer value) { failedAttempts=value; }
    private java.time.OffsetDateTime createdAt;
    public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.OffsetDateTime value) { createdAt=value; }
    private java.time.OffsetDateTime expiresAt;
    public java.time.OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.OffsetDateTime value) { expiresAt=value; }
    private java.time.OffsetDateTime verifiedAt;
    public java.time.OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(java.time.OffsetDateTime value) { verifiedAt=value; }
}
