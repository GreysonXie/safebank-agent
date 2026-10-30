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
 public AgentTurnStatusService(GatewayTurnService turns,GatewayResultService results,HttpAgentClient client){this.turns=turns;this.results=results;this.client=client;}
 public AgentTurnStatusVO status(String user,String id){
  if(user==null||id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw missing();
  var row=turns.read(id);
  if(row==null||!user.equals(row.getUserId()))throw missing();
  return new AgentTurnStatusVO(row.getTurnId(),row.getState(),row.getReconcileFailures(),row.getOutputVersion()==null?0:row.getOutputVersion(),"PAUSED".equals(row.getState()));
 }
 /** 只读解析：按当前会话用户 + conversationId + requestId 定位原轮次，供前端恢复面板使用。 */
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
 /** 受控结束 + 跨侧解锁：Java 事务提交后再做有界内部调用，不在锁内等待网络。 */
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
