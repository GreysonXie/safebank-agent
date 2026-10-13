package cn.safebank.entity;
public class AgentToolCallReceiptEntity {
private String id; public String getId(){return id;} public void setId(String value){id=value;}
private String turnId; public String getTurnId(){return turnId;} public void setTurnId(String value){turnId=value;}
private String conversationId; public String getConversationId(){return conversationId;} public void setConversationId(String value){conversationId=value;}
private String userId; public String getUserId(){return userId;} public void setUserId(String value){userId=value;}
private String operationKey; public String getOperationKey(){return operationKey;} public void setOperationKey(String value){operationKey=value;}
private String inputHash; public String getInputHash(){return inputHash;} public void setInputHash(String value){inputHash=value;}
private String toolName; public String getToolName(){return toolName;} public void setToolName(String value){toolName=value;}
private String grantId; public String getGrantId(){return grantId;} public void setGrantId(String value){grantId=value;}
private String resultJson; public String getResultJson(){return resultJson;} public void setResultJson(String value){resultJson=value;}
private String resultHash; public String getResultHash(){return resultHash;} public void setResultHash(String value){resultHash=value;}
private java.time.OffsetDateTime observedAt; public java.time.OffsetDateTime getObservedAt(){return observedAt;} public void setObservedAt(java.time.OffsetDateTime value){observedAt=value;}
}
