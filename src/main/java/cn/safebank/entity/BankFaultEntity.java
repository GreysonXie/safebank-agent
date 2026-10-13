package cn.safebank.entity;
public class BankFaultEntity {
    private String actionId;
    private String userId;
    private String submitMode;
    private String lookupMode;
    public String getActionId() {
        return actionId;
    }
    public void setActionId(String value) {
        actionId=value;
    }
    public String getUserId() {
        return userId;
    }
    public void setUserId(String value) {
        userId=value;
    }
    public String getSubmitMode() {
        return submitMode;
    }
    public void setSubmitMode(String value) {
        submitMode=value;
    }
    public String getLookupMode() {
        return lookupMode;
    }
    public void setLookupMode(String value) {
        lookupMode=value;
    }
}
