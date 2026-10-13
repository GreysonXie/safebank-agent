package cn.safebank.vo;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public record ConversationVO(String conversationId,long version,String intent,String state,Map<String,JsonNode> slots,JsonNode proposal,List<Map<String,Object>> displayHistory,List<cn.safebank.common.ContextContracts.BankReference> bankReferences) {
    public ConversationVO(String conversationId,long version,String intent,String state,Map<String,JsonNode> slots,JsonNode proposal,List<Map<String,Object>> displayHistory){this(conversationId,version,intent,state,slots,proposal,displayHistory,List.of());}
}
