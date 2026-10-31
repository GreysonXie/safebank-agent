package cn.safebank.entity;
public class TransactionEntity {
    private String id;
    private String userId;
    private String accountId;
    private String merchant;
    private String category;
    private Long amountMinor;
    private java.time.LocalDate bookedOn;
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
    public String getAccountId() {
        return accountId;
    }
    public void setAccountId(String value) {
        accountId=value;
    }
    public String getMerchant() {
        return merchant;
    }
    public void setMerchant(String value) {
        merchant=value;
    }
    public String getCategory() {
        return category;
    }
    public void setCategory(String value) {
        category=value;
    }
    public Long getAmountMinor() {
        return amountMinor;
    }
    public void setAmountMinor(Long value) {
        amountMinor=value;
    }
    public java.time.LocalDate getBookedOn() {
        return bookedOn;
    }
    public void setBookedOn(java.time.LocalDate value) {
        bookedOn=value;
    }
}
