package cn.safebank.service;
import cn.safebank.*;
import cn.safebank.dto.AgentResultDTO;
import cn.safebank.entity.*;
import cn.safebank.dao.AgentGatewayMapper;
import cn.safebank.vo.AgentAckVO;
import cn.safebank.common.ContextContracts.*;
import cn.safebank.common.ContextTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.safebank.service.ContextEvidenceService.*;
@Service @Profile("agent-gateway")
public class GatewayResultService {
 private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GatewayResultService.class);
 private final GatewayTurnService turns;private final AgentCapabilityService capabilities;private final AgentGatewayMapper mapper;private final ContextService contexts;private final ObjectMapper json;private final TransactionTemplate tx;
 @org.springframework.beans.factory.annotation.Autowired private org.springframework.core.env.Environment environment;
 @org.springframework.beans.factory.annotation.Autowired private AgentPreviewService previews;
 @org.springframework.beans.factory.annotation.Autowired private RiskUserLockService riskLocks;
 @org.springframework.beans.factory.annotation.Autowired private AuditService audit;
 @org.springframework.beans.factory.annotation.Autowired private ModelConfigurationService modelSettings;
 public GatewayResultService(GatewayTurnService t,AgentCapabilityService c,AgentGatewayMapper m,ContextService s,ObjectMapper j,PlatformTransactionManager manager){turns=t;capabilities=c;mapper=m;contexts=s;json=j;tx=new TransactionTemplate(manager);}
 public Map<String,Object> validate(String token,AgentResultDTO r){
  if(token==null||!token.matches("[a-f0-9]{64}"))throw GatewayTurnService.denied();var identity=mapper.grant(hash(token));if(identity==null)throw GatewayTurnService.denied();
  return riskLocks.withUserLock(identity.getUserId(),()->tx.execute(status->{var grant=capabilities.authorize(token,true);if(!grant.getTurnId().equals(r.turnId()))throw GatewayTurnService.denied();var g=turns.lock(r.turnId(),true);var text=render(g,r);return Map.of("text",text,"resultHash",resultHash(r));}));
 }
 public AgentAckVO complete(AgentResultDTO r){var identity=mapper.gateway(r.turnId());if(identity==null)throw conflict();return riskLocks.withUserLock(identity.getUserId(),()->tx.execute(status->{
  var g=turns.lock(r.turnId(),false);if(g.getInputVersion()!=r.inputVersion())throw conflict();String h=resultHash(r);
  if(g.getResultHash()!=null){if(!g.getResultHash().equals(h)||g.getOutputVersion()==null)throw conflict();return ack(g);}
  g=turns.lock(r.turnId(),true);String text=render(g,r);var lease=turns.lease(g);var snapshot=contexts.snapshot(g.getUserId(),g.getConversationId());
  var state=r.answer().path("kind").asText().equals("clarification")?ConversationState.NEEDS_CLARIFICATION:ConversationState.ACTIVE;
  var next=new ContextSnapshot(snapshot.conversationId(),snapshot.version(),Intent.GENERAL,state,Map.of(),null,List.of(),snapshot.displayHistory());
  var response=new LinkedHashMap<String,Object>();response.put("text",text);response.put("mode","FAKE_AGENT");response.put("agentBackend","FAKE_PYTHON_PROTOCOL");response.put("toolResults",mapper.receipts(g.getTurnId()).stream().map(x->Map.of("tool",x.getToolName(),"result",tree(x.getResultJson()),"evidenceId",x.getId(),"observedAt",x.getObservedAt().toString())).toList());
  var displayEvidence=capabilities.evidence(mapper.receipts(g.getTurnId()));
  response.put("results",displayEvidence);response.put("data",displayEvidence.isEmpty()?Map.of():displayEvidence.getLast().data());response.put("actions",previews.cards(g.getUserId(),g.getConversationId()));response.put("trace",List.of());response.put("answerSource","PROGRAM_VERIFIED_FAKE_AGENT");
  var taskRows=mapper.receipts(g.getTurnId()).stream().filter(row->Set.of("savings_task_prepare","savings_task_status").contains(row.getToolName())).toList();
  if(!taskRows.isEmpty()){
   var task=tree(taskRows.getLast().getResultJson());
   if(!task.has("code")){
    response.put("data",Map.of("workflow",task));
    var cards=new ArrayList<Object>(previews.cards(g.getUserId(),g.getConversationId()));
    if(task.path("pendingAction").has("id"))cards.add(task.get("pendingAction"));
    response.put("actions",cards);
   }
  }
  response.put("clarification",Map.of("required",state==ConversationState.NEEDS_CLARIFICATION));
  if(environment.matchesProfiles("langgraph-agent")){response.put("mode",onlineMode(g.getUserId())?"LANGGRAPH_MODEL":"LANGGRAPH_MOCK");response.put("agentBackend","PYTHON_LANGGRAPH");response.put("answerSource","PROGRAM_VERIFIED_LANGGRAPH");}
  var evidence=new ArrayList<EvidenceInput>();for(var row:mapper.receipts(g.getTurnId())){var data=tree(row.getResultJson());if(row.getToolName().equals("statement"))evidence.add(new EvidenceInput("gateway:"+row.getId(),"get_statement",null,data,row.getObservedAt().toInstant(),null));}
  contexts.complete(lease,new ContextCommit(next,evidence,response));
  g.setResultHash(h);g.setResultJson(encode(r));g.setOutputVersion(g.getInputVersion()+1);g.setState("ACK_PENDING");g.setReconcileFailures(0);g.setNextCheckAt(turns.now().plusSeconds(30));require(mapper.updateGateway(g));mapper.revoke(g.getUserId(),g.getTurnId());return ack(g);
 }));}
 /** 与 /api/session 同一口径：真实模型是否在役要看本机已保存的配置，而不是部署环境变量。 */
 private boolean onlineMode(String userId){
  if(modelSettings==null)return Set.of("deepseek","openai").contains(environment.getProperty("bank.agent.model-mode","mock"));
  try{
   var config=modelSettings.settings(userId);
   return "local".equals(config.source())?config.enabled()&&config.hasApiKey():Set.of("deepseek","openai").contains(environment.getProperty("bank.agent.model-mode","mock"));
  }catch(RuntimeException failure){return false;}
 }
 private String render(AgentGatewayTurnEntity g,AgentResultDTO r){
  if(r.inputVersion()!=g.getInputVersion()||r.answer()==null||!r.answer().isObject()||r.answer().toString().length()>8192||r.evidenceIds()==null||r.evidenceIds().size()>8||new HashSet<>(r.evidenceIds()).size()!=r.evidenceIds().size())throw conflict();
  var keys=new HashSet<String>();r.answer().fieldNames().forEachRemaining(keys::add);if(!keys.equals(Set.of("kind","answer")))throw conflict();
  var all=mapper.receipts(g.getTurnId());var selected=all.stream().filter(x->r.evidenceIds().contains(x.getId())).toList();if(selected.size()!=r.evidenceIds().size())throw conflict();
  for(var row:selected)if(!hash(row.getResultJson()).equals(row.getResultHash()))throw conflict();
  return new GroundedAnswer(json,capabilities.evidence(selected)).render(r.answer()).text();
 }
 public String resultHash(AgentResultDTO r){return capabilities.inputHash("agent_result",json.valueToTree(r));}
 public AgentAckVO saved(String turnId){return tx.execute(s->{var g=turns.lock(turnId,false);if(g.getOutputVersion()==null)throw conflict();return ack(g);});}
 private AgentAckVO ack(AgentGatewayTurnEntity g){var row=mapper.lockTurn(g.getUserId(),g.getTurnId());if(!row.getState().equals("COMPLETED")||!Objects.equals(row.getOutputVersion(),g.getOutputVersion()))throw conflict();try{return new AgentAckVO(g.getTurnId(),g.getOutputVersion(),g.getResultHash(),json.readValue(row.getResponseJson(),new TypeReference<Map<String,Object>>(){}));}catch(java.io.IOException e){throw new IllegalStateException(e);}}
 public void acknowledged(String turnId,String resultHash,long outputVersion){tx.executeWithoutResult(s->{var g=turns.lock(turnId,false);if(!Objects.equals(g.getResultHash(),resultHash)||!Objects.equals(g.getOutputVersion(),outputVersion))throw conflict();g.setState("ACKED");g.setReconcileFailures(0);require(mapper.updateGateway(g));});}
 /** 受控结束：只有“已暂停且没有可核验结果”的轮次可以由用户结束；终态保留原请求与证据。 */
 public java.util.Map<String,Object> close(String turnId){return tx.execute(s->{var g=turns.lock(turnId,false);
  if("INTERRUPTED".equals(g.getState())){var already=new java.util.LinkedHashMap<String,Object>();already.put("state","INTERRUPTED");already.put("hasOutput",false);already.put("closed",true);return already;}
  if("ACKED".equals(g.getState()))throw new BankException("CONFLICT","本轮已完成，请先核对原回答，不能当作未完成轮次结束");
  if(!g.getState().equals("PAUSED"))throw new BankException("CONFLICT","本轮仍在执行或结果未定，请先核对原单再结束");
  if(g.getOutputVersion()!=null)throw new BankException("CONFLICT","本轮已有可核验结果，请先核对原回答");
  contexts.interrupt(turns.lease(g));g.setState("INTERRUPTED");g.setNextCheckAt(turns.now().plusSeconds(30));note(g,"AGENT_CLOSED_BY_USER","USER_CLOSED_AFTER_REVIEW");require(mapper.updateGateway(g));mapper.revoke(g.getUserId(),turnId);
  LOG.warn("Agent turn {} closed by user after review",turnId);
  var out=new java.util.LinkedHashMap<String,Object>();out.put("state","INTERRUPTED");out.put("hasOutput",false);out.put("closed",true);return out;});}
 public void deferred(String turnId,boolean unavailable){deferred(turnId,unavailable,"AGENT_RESULT_PENDING");}
 /** 失败原因不再只留 +1/−1 计数：暂停/中断时把真实原因码写入审计，供操作者核对。 */
 public void deferred(String turnId,boolean unavailable,String reason){tx.executeWithoutResult(s->{var g=turns.lock(turnId,false);if(Set.of("ACKED","INTERRUPTED").contains(g.getState()))return;g.setNextCheckAt(turns.now().plusSeconds(30));if(unavailable){g.setReconcileFailures(Math.min(3,g.getReconcileFailures()+1));if(g.getReconcileFailures()==3){g.setState("PAUSED");note(g,"AGENT_RECONCILE_PAUSED",reason);LOG.warn("Agent reconciliation paused; original turn {} requires review ({})",turnId,reason);}}else g.setReconcileFailures(0);require(mapper.updateGateway(g));});}
 public void noRun(String turnId){noRun(turnId,"AGENT_RUN_MISSING");}
 public void noRun(String turnId,String reason){tx.executeWithoutResult(s->{var g=turns.lock(turnId,false);if(g.getOutputVersion()!=null){g.setState("PAUSED");note(g,"AGENT_RESULT_REVIEW",reason);}else{contexts.interrupt(turns.lease(g));g.setState("INTERRUPTED");note(g,"AGENT_INTERRUPTED",reason);}mapper.revoke(g.getUserId(),turnId);g.setNextCheckAt(turns.now().plusSeconds(30));require(mapper.updateGateway(g));});}
 public void interrupted(String turnId){noRun(turnId,"AGENT_PERMISSION_DENIED");}
 /** 本轮确定失败且没有任何银行写操作：置为终态并保留真实原因，客户可直接继续提问，不用暂停占住会话。 */
 public void failed(String turnId,String reason){tx.executeWithoutResult(s->{var g=turns.lock(turnId,false);
  String safe=reason==null||reason.isBlank()?"AGENT_FAILED":reason;
  if(g.getOutputVersion()!=null){g.setState("PAUSED");note(g,"AGENT_RESULT_REVIEW",safe);}
  else{contexts.interrupt(turns.lease(g));g.setState("INTERRUPTED");note(g,"AGENT_FAILED",safe);}
  mapper.revoke(g.getUserId(),turnId);g.setNextCheckAt(turns.now().plusSeconds(30));require(mapper.updateGateway(g));
  LOG.warn("Agent turn {} failed without bank effect ({})",turnId,safe);});}
 public void pause(String turnId){pause(turnId,"AGENT_RESULT_REVIEW");}
 public void pause(String turnId,String reason){tx.executeWithoutResult(s->{var g=turns.lock(turnId,false);if(g.getState().equals("ACKED"))return;g.setState("PAUSED");g.setNextCheckAt(turns.now().plusSeconds(30));note(g,"AGENT_PAUSED",reason);require(mapper.updateGateway(g));LOG.warn("Agent result requires review; original turn {} retained ({})",turnId,reason);});}
 /** 审计只记原因，不记密钥、凭证或模型正文。 */
 private void note(AgentGatewayTurnEntity g,String event,String reason){
  if(audit==null)return;
  String safe=reason==null||reason.isBlank()?"UNSPECIFIED":reason.replaceAll("[^A-Za-z0-9_.:/-]","_").substring(0,Math.min(120,reason.length()));
  try{audit.record(g.getUserId(),g.getTurnId(),event+" · "+safe);}catch(RuntimeException failure){LOG.warn("Audit write failed for turn {} ({})",g.getTurnId(),event);}
 }
 private JsonNode tree(String s){try{return json.readTree(s);}catch(Exception e){throw new IllegalStateException(e);}}
 private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
 private static BankException conflict(){return new BankException("CONFLICT","原结果、证据或输入版本不一致，停止自动完成");}
}
