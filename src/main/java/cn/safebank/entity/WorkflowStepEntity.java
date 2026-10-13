package cn.safebank.entity;
public class WorkflowStepEntity {
    private String workflowId;
    private String stepKey;
    private Long inputVersion;
    private String kind;
    private String status;
    private String actionId;
    private Long savingMinor;
    private String errorCode;
    private String evidenceJson;
    public String getWorkflowId() {
        return workflowId;
    }
    public void setWorkflowId(String value) {
        workflowId=value;
    }
    public String getStepKey() {
        return stepKey;
    }
    public void setStepKey(String value) {
        stepKey=value;
    }
    public Long getInputVersion() {
        return inputVersion;
    }
    public void setInputVersion(Long value) {
        inputVersion=value;
    }
    public String getKind() {
        return kind;
    }
    public void setKind(String value) {
        kind=value;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String value) {
        status=value;
    }
    public String getActionId() {
        return actionId;
    }
    public void setActionId(String value) {
        actionId=value;
    }
    public Long getSavingMinor() {
        return savingMinor;
    }
    public void setSavingMinor(Long value) {
        savingMinor=value;
    }
    public String getErrorCode() {
        return errorCode;
    }
    public void setErrorCode(String value) {
        errorCode=value;
    }
    public String getEvidenceJson() {
        return evidenceJson;
    }
    public void setEvidenceJson(String value) {
        evidenceJson=value;
    }
}
