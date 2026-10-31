package cn.safebank.entity;
public class WorkflowEntity {
    private String id;
    private String userId;
    private Long version;
    private String state;
    private String requestJson;
    private String analysisJson;
    private java.time.OffsetDateTime createdAt;
    private java.time.OffsetDateTime updatedAt;
    public String getId() {
        return id;
    }
    public void setId(String value) {
        id=value;
    }
    public String getUserId() {
        return userId;
    }
    public void setUserId(String value) {
        userId=value;
    }
    public Long getVersion() {
        return version;
    }
    public void setVersion(Long value) {
        version=value;
    }
    public String getState() {
        return state;
    }
    public void setState(String value) {
        state=value;
    }
    public String getRequestJson() {
        return requestJson;
    }
    public void setRequestJson(String value) {
        requestJson=value;
    }
    public String getAnalysisJson() {
        return analysisJson;
    }
    public void setAnalysisJson(String value) {
        analysisJson=value;
    }
    public java.time.OffsetDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(java.time.OffsetDateTime value) {
        createdAt=value;
    }
    public java.time.OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(java.time.OffsetDateTime value) {
        updatedAt=value;
    }
}
