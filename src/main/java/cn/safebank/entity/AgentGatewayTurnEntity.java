package cn.safebank.entity;
public class AgentGatewayTurnEntity {
private String turnId; public String getTurnId(){return turnId;} public void setTurnId(String value){turnId=value;}
private String conversationId; public String getConversationId(){return conversationId;} public void setConversationId(String value){conversationId=value;}
private String userId; public String getUserId(){return userId;} public void setUserId(String value){userId=value;}
private String requestId; public String getRequestId(){return requestId;} public void setRequestId(String value){requestId=value;}
private String userText; public String getUserText(){return userText;} public void setUserText(String value){userText=value;}
private String resultHash; public String getResultHash(){return resultHash;} public void setResultHash(String value){resultHash=value;}
private String resultJson; public String getResultJson(){return resultJson;} public void setResultJson(String value){resultJson=value;}
private String state; public String getState(){return state;} public void setState(String value){state=value;}
private Long inputVersion; public Long getInputVersion(){return inputVersion;} public void setInputVersion(Long value){inputVersion=value;}
private Long outputVersion; public Long getOutputVersion(){return outputVersion;} public void setOutputVersion(Long value){outputVersion=value;}
private Integer reconcileFailures; public Integer getReconcileFailures(){return reconcileFailures;} public void setReconcileFailures(Integer value){reconcileFailures=value;}
private java.time.OffsetDateTime sessionExpiresAt; public java.time.OffsetDateTime getSessionExpiresAt(){return sessionExpiresAt;} public void setSessionExpiresAt(java.time.OffsetDateTime value){sessionExpiresAt=value;}
private java.time.OffsetDateTime nextCheckAt; public java.time.OffsetDateTime getNextCheckAt(){return nextCheckAt;} public void setNextCheckAt(java.time.OffsetDateTime value){nextCheckAt=value;}
private java.time.OffsetDateTime createdAt; public java.time.OffsetDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(java.time.OffsetDateTime value){createdAt=value;}
}
