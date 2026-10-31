package cn.safebank.vo;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import cn.safebank.GroundedAnswer;
public record AgentToolReceiptVO(String evidenceId,String operationKey,String resultHash,Instant observedAt,String tool,JsonNode data,Map<String,GroundedAnswer.Fact> facts,boolean replay,Instant sourceQueriedAt,Instant sourceExpiresAt) {
 public AgentToolReceiptVO(String evidenceId,String operationKey,String resultHash,Instant observedAt,String tool,JsonNode data,Map<String,GroundedAnswer.Fact> facts,boolean replay){this(evidenceId,operationKey,resultHash,observedAt,tool,data,facts,replay,null,null);}
}