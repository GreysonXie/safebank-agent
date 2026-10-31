package cn.safebank.entity;
public class MandateEntity {
    private String id;
    private String userId;
    private String merchant;
    private Long amountMinor;
    private java.time.LocalDate renewalDate;
    private String status;
    private String contractStatus;
    private String billingInterval;
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
    public String getMerchant() {
        return merchant;
    }
    public void setMerchant(String value) {
        merchant=value;
    }
    public Long getAmountMinor() {
        return amountMinor;
    }
    public void setAmountMinor(Long value) {
        amountMinor=value;
    }
    public java.time.LocalDate getRenewalDate() {
        return renewalDate;
    }
    public void setRenewalDate(java.time.LocalDate value) {
        renewalDate=value;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String value) {
        status=value;
    }
    public String getContractStatus() {
        return contractStatus;
    }
    public void setContractStatus(String value) {
        contractStatus=value;
    }
    public String getBillingInterval() {
        return billingInterval;
    }
    public void setBillingInterval(String value) {
        billingInterval=value;
    }
}
