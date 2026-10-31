package cn.safebank.entity;
public class AgentMandateQueryReceiptEntity {
 private String snapshotJson;
 public String getSnapshotJson(){return snapshotJson;} public void setSnapshotJson(String value){snapshotJson=value;}
 private Boolean invalidated;
 public Boolean getInvalidated(){return invalidated;} public void setInvalidated(Boolean value){invalidated=value;}
 private String conversationId;
 public String getConversationId(){return conversationId;} public void setConversationId(String value){conversationId=value;}
 private String actionId;
 public String getActionId(){return actionId;} public void setActionId(String value){actionId=value;}
 private String snapshotHash;
 public String getSnapshotHash(){return snapshotHash;} public void setSnapshotHash(String value){snapshotHash=value;}
 private java.time.OffsetDateTime expiresAt;
 public java.time.OffsetDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(java.time.OffsetDateTime value){expiresAt=value;}
 private String userId;
 public String getUserId(){return userId;} public void setUserId(String value){userId=value;}
 private Long boundVersion;
 public Long getBoundVersion(){return boundVersion;} public void setBoundVersion(Long value){boundVersion=value;}
 private java.time.OffsetDateTime queriedAt;
 public java.time.OffsetDateTime getQueriedAt(){return queriedAt;} public void setQueriedAt(java.time.OffsetDateTime value){queriedAt=value;}
}
