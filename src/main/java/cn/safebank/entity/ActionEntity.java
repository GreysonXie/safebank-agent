package cn.safebank.entity;
public class ActionEntity {
    private String id;
    private String userId;
    private String kind;
    private String payload;
    private String payloadHash;
    private String status;
    private java.time.OffsetDateTime expiresAt;
    private String receiptId;
    private String errorCode;
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
    public String getKind() {
        return kind;
    }
    public void setKind(String value) {
        kind=value;
    }
    public String getPayload() {
        return payload;
    }
    public void setPayload(String value) {
        payload=value;
    }
    public String getPayloadHash() {
        return payloadHash;
    }
    public void setPayloadHash(String value) {
        payloadHash=value;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String value) {
        status=value;
    }
    public java.time.OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
    public void setExpiresAt(java.time.OffsetDateTime value) {
        expiresAt=value;
    }
    public String getReceiptId() {
        return receiptId;
    }
    public void setReceiptId(String value) {
        receiptId=value;
    }
    public String getErrorCode() {
        return errorCode;
    }
    public void setErrorCode(String value) {
        errorCode=value;
    }
}
