package cn.safebank.entity;
public class ScheduleAuthorizationEntity {
    private String scheduleId;
    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String value) { scheduleId=value; }
    private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { userId=value; }
    private String sourceAuthorizationId;
    public String getSourceAuthorizationId() { return sourceAuthorizationId; }
    public void setSourceAuthorizationId(String value) { sourceAuthorizationId=value; }
    private String scopeJson;
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String value) { scopeJson=value; }
    private String scopeHash;
    public String getScopeHash() { return scopeHash; }
    public void setScopeHash(String value) { scopeHash=value; }
    private String policyVersion;
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion=value; }
    private String maximumRiskLevel;
    public String getMaximumRiskLevel() { return maximumRiskLevel; }
    public void setMaximumRiskLevel(String value) { maximumRiskLevel=value; }
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    private java.time.OffsetDateTime createdAt;
    public java.time.OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.OffsetDateTime value) { createdAt=value; }
    private java.time.OffsetDateTime expiresAt;
    public java.time.OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.OffsetDateTime value) { expiresAt=value; }
}
