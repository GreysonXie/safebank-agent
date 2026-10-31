package cn.safebank.entity;
public class LedgerEntryEntity {
    private String id;
    private String actionId;
    private String accountId;
    private Long amountMinor;
    public String getId() {
        return id;
    }
    public void setId(String value) {
        id=value;
    }
    public String getActionId() {
        return actionId;
    }
    public void setActionId(String value) {
        actionId=value;
    }
    public String getAccountId() {
        return accountId;
    }
    public void setAccountId(String value) {
        accountId=value;
    }
    public Long getAmountMinor() {
        return amountMinor;
    }
    public void setAmountMinor(Long value) {
        amountMinor=value;
    }
}
