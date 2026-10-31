package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.agent.HttpAgentClient;
import cn.safebank.vo.AgentTurnStatusVO;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service @Profile("agent-gateway")
public class AgentTurnStatusService {
 private final GatewayTurnService turns;
 private final GatewayResultService results;
 private final HttpAgentClient client;
 private final cn.safebank.dao.AgentGatewayMapper receipts;
 private final com.fasterxml.jackson.databind.ObjectMapper json;
 public AgentTurnStatusService(GatewayTurnService turns,GatewayResultService results,HttpAgentClient client,
                               cn.safebank.dao.AgentGatewayMapper receipts,com.fasterxml.jackson.databind.ObjectMapper json){
  this.turns=turns;this.results=results;this.client=client;this.receipts=receipts;this.json=json;
 }
 /**
  * 侧栏跟随对话口径：读取该用户该会话最近一次 statement_query 回执里的口径与结果快照。
  * 只读回执，不发起新查询、不写库；没有该工具时 found=false，界面保持默认月度/年度视图。
  */
 public Map<String,Object> statementQuery(String user,String conversationId){
  if(user==null||conversationId==null||!conversationId.matches("[A-Za-z0-9_-]{1,80}"))
   throw new BankException("VALIDATION_ERROR","缺少有效的对话编号");
  var out=new LinkedHashMap<String,Object>();
  var receipt=receipts.latestReceiptByTool(user,conversationId,"statement_query");
  if(receipt==null||receipt.getResultJson()==null){out.put("found",false);return out;}
  try{
   var result=json.readTree(receipt.getResultJson());
   if(!result.has("query")){out.put("found",false);return out;}
   out.put("found",true);
   out.put("query",result.get("query"));
   out.put("result",result);
   out.put("observedAt",receipt.getObservedAt()==null?null:receipt.getObservedAt().toInstant().toString());
  }catch(java.io.IOException broken){
   out.put("found",false);out.put("broken",true);
  }
  return out;
 }
 /**
  * 对话驱动的卡片预览：读取该用户该会话最近一次 card_preview 回执，供卡片页直接打开待确认预览。
  * 只读回执，不确认、不执行；没有预览时 found=false，界面保持原状。
  */
 public Map<String,Object> cardOperation(String user,String conversationId){
  if(user==null||conversationId==null||!conversationId.matches("[A-Za-z0-9_-]{1,80}"))
   throw new BankException("VALIDATION_ERROR","缺少有效的对话编号");
  var out=new LinkedHashMap<String,Object>();
  var receipt=receipts.latestReceiptByTool(user,conversationId,"card_preview");
  if(receipt==null||receipt.getResultJson()==null){out.put("found",false);return out;}
  try{
   var result=json.readTree(receipt.getResultJson());
   var operation=result.path("pendingCardOperation");
   if(!operation.isObject()){out.put("found",false);return out;}
   out.put("found",true);
   out.put("operation",operation);
   out.put("observedAt",receipt.getObservedAt()==null?null:receipt.getObservedAt().toInstant().toString());
  }catch(java.io.IOException broken){
   out.put("found",false);out.put("broken",true);
  }
  return out;
 }
 public AgentTurnStatusVO status(String user,String id){
  if(user==null||id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw missing();
  var row=turns.read(id);
  if(row==null||!user.equals(row.getUserId()))throw missing();
  return new AgentTurnStatusVO(row.getTurnId(),row.getState(),row.getReconcileFailures(),row.getOutputVersion()==null?0:row.getOutputVersion(),"PAUSED".equals(row.getState()));
 }
 /** 按当前会话用户 + conversationId + requestId 定位原轮次，供恢复面板显示原请求状态。 */
 public Map<String,Object> resolve(String user,String conversationId,String requestId){
  var row=turns.original(user,conversationId,requestId);
  var out=new LinkedHashMap<String,Object>();
  out.put("turnId",row.getTurnId());
  out.put("state",row.getState());
  out.put("requiresReview","PAUSED".equals(row.getState()));
  out.put("hasOutput",row.getOutputVersion()!=null);
  out.put("closed","INTERRUPTED".equals(row.getState()));
  if(row.getOutputVersion()!=null){
   try{out.put("answer",results.saved(row.getTurnId()).response());}catch(RuntimeException ignore){out.put("answerMissing",true);}
  }
  return out;
 }
 /**
  * 受控结束 + 跨侧解锁：先把本侧轮次置为终态并提交，再做有界内部调用通知助手结束占用，
  * 不在数据库锁内等待网络；助手不可用只记为 AGENT_UNAVAILABLE，不影响本侧已结束的事实。
  */
 public Map<String,Object> close(String user,String conversationId,String requestId){
  if(conversationId==null||conversationId.isBlank())throw new BankException("VALIDATION_ERROR","缺少 conversationId");
  var row=turns.original(user,conversationId,requestId);
  var outcome=new LinkedHashMap<String,Object>(results.close(row.getTurnId()));
  if("INTERRUPTED".equals(outcome.get("state"))){
   try{
    var reply=client.abandonBlocking(user,conversationId,row.getTurnId());
    outcome.put("agentUnblocked",reply.path("unblocked").asBoolean(false));
    outcome.put("agentReason",reply.path("reason").asText(""));
    if(reply.hasNonNull("blockingTurnId"))outcome.put("blockingTurnId",reply.path("blockingTurnId").asText());
   }catch(RuntimeException e){
    outcome.put("agentUnblocked",false);outcome.put("agentReason","AGENT_UNAVAILABLE");
   }
  }
  return outcome;
 }
 private static BankException missing(){return new BankException("NOT_FOUND","轮次不存在或不属于当前会话");}
}
