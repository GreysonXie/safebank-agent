package cn.safebank.entity;
public class RecipientEntity {
    private String id;
    private String userId;
    private String name;
    private String phone;
    private String note;
    private String accountId;
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
    public String getName() {
        return name;
    }
    public void setName(String value) {
        name=value;
    }
    public String getPhone() {
        return phone;
    }
    public void setPhone(String value) {
        phone=value;
    }
    public String getNote() {
        return note;
    }
    public void setNote(String value) {
        note=value;
    }
    public String getAccountId() {
        return accountId;
    }
    public void setAccountId(String value) {
        accountId=value;
    }
}
