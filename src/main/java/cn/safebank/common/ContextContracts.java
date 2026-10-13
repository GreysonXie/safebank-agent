package cn.safebank.common;
import static cn.safebank.common.ContextTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
public final class ContextContracts {
    private ContextContracts() {}
    public record TurnLease(String userId,String conversationId,String turnId,long inputVersion,String requestId,String userText) {
        public TurnLease(String userId,String conversationId,String turnId,long inputVersion,String requestId){this(userId,conversationId,turnId,inputVersion,requestId,"");}
    }
    public record BankReference(String toolCallId,String kind,String bankObjectId) {}
    public record ToolIntent(String toolCallId,String toolName,String parametersHash,String bankObjectId,String state) {}
    public record SlotValue(JsonNode value,SlotProvenance provenance,String sourceTurnId,String evidenceId) {}
    public record EvidenceInput(String key,String toolName,String objectId,JsonNode data,Instant observedAt,Instant validUntil) {}
    public record ContextSnapshot(String conversationId,long version,Intent intent,ConversationState state,Map<SlotName,SlotValue> slots,JsonNode proposal,List<EvidenceInput> evidence,List<Map<String,Object>> displayHistory) {}
    public record TurnStart(TurnLease lease,Map<String,Object> replayResponse,boolean processing) {}
    public record ContextCommit(ContextSnapshot snapshot,List<EvidenceInput> evidence,Map<String,Object> response) {}
}
