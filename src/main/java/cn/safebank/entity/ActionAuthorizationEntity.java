package cn.safebank.entity;
public class ActionAuthorizationEntity {
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
    private String challengeId;
    public String getChallengeId() { return challengeId; }
    public void setChallengeId(String value) { challengeId=value; }
    private String payloadHash;
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { payloadHash=value; }
    private String policyVersion;
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion=value; }
    private java.time.LocalDate businessDate;
    public java.time.LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(java.time.LocalDate value) { businessDate=value; }
    private String method;
    public String getMethod() { return method; }
    public void setMethod(String value) { method=value; }
    private String assurance;
    public String getAssurance() { return assurance; }
    public void setAssurance(String value) { assurance=value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    private java.time.OffsetDateTime authorizedAt;
    public java.time.OffsetDateTime getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(java.time.OffsetDateTime value) { authorizedAt=value; }
    private java.time.OffsetDateTime expiresAt;
    public java.time.OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.OffsetDateTime value) { expiresAt=value; }
}
