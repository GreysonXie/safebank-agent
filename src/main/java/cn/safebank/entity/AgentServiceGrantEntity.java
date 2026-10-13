package cn.safebank.entity;
public class AgentServiceGrantEntity {
private String id; public String getId(){return id;} public void setId(String value){id=value;}
private String tokenHash; public String getTokenHash(){return tokenHash;} public void setTokenHash(String value){tokenHash=value;}
private String userId; public String getUserId(){return userId;} public void setUserId(String value){userId=value;}
private String conversationId; public String getConversationId(){return conversationId;} public void setConversationId(String value){conversationId=value;}
private String turnId; public String getTurnId(){return turnId;} public void setTurnId(String value){turnId=value;}
private String requestId; public String getRequestId(){return requestId;} public void setRequestId(String value){requestId=value;}
private String scopeJson; public String getScopeJson(){return scopeJson;} public void setScopeJson(String value){scopeJson=value;}
private String state; public String getState(){return state;} public void setState(String value){state=value;}
private Integer callLimit; public Integer getCallLimit(){return callLimit;} public void setCallLimit(Integer value){callLimit=value;}
private Integer callsUsed; public Integer getCallsUsed(){return callsUsed;} public void setCallsUsed(Integer value){callsUsed=value;}
private java.time.OffsetDateTime createdAt; public java.time.OffsetDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(java.time.OffsetDateTime value){createdAt=value;}
private java.time.OffsetDateTime expiresAt; public java.time.OffsetDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(java.time.OffsetDateTime value){expiresAt=value;}
}
