package cn.safebank.dto;
public record QueryIntentRequestDTO(String operation,String conversationId,Long expectedContextVersion) {
 public QueryIntentRequestDTO(String operation){this(operation,null,null);}
}