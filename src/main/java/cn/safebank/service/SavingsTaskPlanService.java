package cn.safebank.service;
import cn.safebank.common.TaskNodeType;
import cn.safebank.dto.*;
import cn.safebank.dao.AgentGatewayMapper;
import cn.safebank.dao.AgentTurnReceiptMapper;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Compile validated intents to existing workflows, never authorize or execute. */
@Service @Profile("agent-gateway") public class SavingsTaskPlanService {
 private final AgentMandateReceiptService receipts;private final AccountService accounts;private final RecipientService recipients;
 private final WorkflowService workflows;private final ObjectMapper json;private final Clock clock;
 private final AgentGatewayMapper gateway;private final AgentTurnReceiptMapper turns;
 public SavingsTaskPlanService(AgentMandateReceiptService receipts,AccountService accounts,RecipientService recipients,WorkflowService workflows,ObjectMapper json,Clock clock,AgentGatewayMapper gateway,AgentTurnReceiptMapper turns){this.receipts=receipts;this.accounts=accounts;this.recipients=recipients;this.workflows=workflows;this.json=json;this.clock=clock;this.gateway=gateway;this.turns=turns;}
 private static Map<String,String> notice(String code){return Map.of("code",code);}
 private static String normalized(String value){return value.replace("（合成）","").replace("(合成)","").strip();}
 public Object prepare(String user,String conversationId,String turnId,String operationKey,SavingsTaskPlanDTO proposal){
  // Revalidate even for direct service callers. Python validation is not trusted.
  proposal=new SavingsTaskPlanValidator(json).validate(json.valueToTree(proposal));
  for(var turn:turns.selectByConversation(user,conversationId))for(var receipt:gateway.receipts(turn.getId())){
   if(!receipt.getToolName().equals("savings_task_prepare")||receipt.getTurnId().equals(turnId))continue;
   if(!ContextEvidenceService.hash(receipt.getResultJson()).equals(receipt.getResultHash()))throw new cn.safebank.BankException("CONFLICT","原任务回执完整性校验失败");
   try{
    var prior=json.readTree(receipt.getResultJson());if(prior.has("code")||!prior.has("id"))continue;
    var original=workflows.get(user,UUID.fromString(prior.path("id").asText()));
    if(!Set.of("VERIFIED_SUCCESS","PARTIAL_SUCCESS","CANCELLED","VERIFIED_FAILED").contains(original.state()))return notice("TASK_ALREADY_EXISTS");
   }catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new cn.safebank.BankException("CONFLICT","原任务回执无法读取");}
  }
  JsonNode snapshot=json.valueToTree(receipts.read(user,conversationId));
  if(snapshot.has("code"))return notice("QUERY_CONFIRMATION_REQUIRED");
  receipts.requireValidSnapshot(user,conversationId,snapshot);
  var ids=new ArrayList<String>();
  for(var node:proposal.nodes())if(node.type()==TaskNodeType.mandate_revoke){
   String query=normalized(node.params().path("merchantQuery").asText());var matches=new ArrayList<JsonNode>();
   for(var item:snapshot.path("mandates"))if(normalized(item.path("merchant").asText()).equals(query))matches.add(item);
   if(matches.size()!=1)return notice("MANDATE_CLARIFICATION_REQUIRED");
   var item=matches.getFirst();
   if(!"ACTIVE".equals(item.path("status").asText()))return notice("MANDATE_NOT_ACTIVE");
   if(!"MONTHLY".equals(item.path("billing_interval").asText()))return notice("MANDATE_NOT_MONTHLY");
   String id=item.path("id").asText();if(id.isBlank()||ids.contains(id))return notice("MANDATE_CLARIFICATION_REQUIRED");ids.add(id);
  }
  var target=proposal.nodes().stream().filter(n->n.type()==TaskNodeType.schedule_create).findFirst().orElseThrow();var p=target.params();
  String source=normalized(p.path("sourceQuery").asText());
  var sources=accounts.list(user).stream().filter(a->a.id().equals(source)||normalized(a.label()).equals(source)).toList();
  if(sources.size()!=1)return notice("SOURCE_CLARIFICATION_REQUIRED");
  var targets=recipients.search(user,p.path("recipientQuery").asText());
  if(targets.size()!=1)return notice("RECIPIENT_CLARIFICATION_REQUIRED");
  String month=proposal.analysisMonth()==null?YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString():proposal.analysisMonth();
  var request=new WorkflowRequestDTO(month,ids,sources.getFirst().id(),targets.getFirst().id(),p.path("firstDate").asText(),p.path("endDate").asText(),p.path("dayOfMonth").intValue(),true);
  UUID workflowId=UUID.nameUUIDFromBytes(("savings-task\n"+turnId+"\n"+operationKey).getBytes(StandardCharsets.UTF_8));
  return workflows.createIdentified(user,workflowId,request);
 }
}
