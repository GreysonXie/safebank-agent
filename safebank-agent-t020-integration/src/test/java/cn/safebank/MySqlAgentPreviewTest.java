package cn.safebank;
import cn.safebank.dto.*;
import cn.safebank.service.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlAgentPreviewTest {
 static final Instant NOW=Instant.parse("2026-10-11T02:00:00Z");static ConfigurableApplicationContext app;static JdbcTemplate db;
 static ContextService contexts;static RiskQueryService queries;static AgentCapabilityService caps;static GatewayTurnService turns;static ObjectMapper json;static MutableRiskClock clock;
 @org.springframework.boot.test.context.TestConfiguration static class TimeConfig {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary MutableRiskClock testClock(){return new MutableRiskClock(NOW);}
 }
 @BeforeEach void start(){var ds=(DriverManagerDataSource)MySqlTestSupport.fresh("preview");app=new SpringApplicationBuilder(Application.class,TimeConfig.class).profiles("mysql","mysql-test","agent-gateway","langgraph-agent").run("--server.port=0","--spring.datasource.url="+ds.getUrl(),"--bank.agent.service-key="+"x".repeat(64),"--bank.agent.reconcile.enabled=false","--bank.model.debug-budget.path="+System.getProperty("java.io.tmpdir")+"/mandate-receipt-budget-"+UUID.randomUUID()+".json","--logging.level.root=ERROR","--spring.main.banner-mode=off");db=new JdbcTemplate(ds);new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/mysql/v07-synthetic.sql")).execute(ds);contexts=app.getBean(ContextService.class);queries=app.getBean(RiskQueryService.class);caps=app.getBean(AgentCapabilityService.class);turns=app.getBean(GatewayTurnService.class);json=app.getBean(ObjectMapper.class);clock=app.getBean(MutableRiskClock.class);}
 @AfterEach void close(){if(app!=null)app.close();}
 static AgentToolRequestDTO previewRequest(String key,String tool,Object args){var node=json.valueToTree(args);return new AgentToolRequestDTO(ContextEvidenceService.hash(key),caps.inputHash(tool,node),tool,node);}
 @Test void savingsGraphPreparationReplayAndExecutionUseOriginalWorkflow() throws Exception {
  String cid=contexts.create("u1");confirmed(cid);String grant=token(cid);
  var plan=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("agent-service/tests/fixtures/savings_task_plan.json")));
  plan.put("analysisMonth","2026-09");((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("nodes").get(5).path("params")).put("recipientQuery","旅游基金");
  int ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class),auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class);
  var request=previewRequest("savings-original","savings_task_prepare",Map.of("taskPlan",plan));var first=caps.call(grant,request);var replay=caps.call(grant,request);
  assertEquals(first.data().path("id"),replay.data().path("id"));assertTrue(replay.replay());assertTrue(first.facts().containsKey("savings_task.phase"));
  assertEquals("〔音乐服务〕",first.facts().get("savings_task.pending.merchant").display());assertEquals("2026-11-20",first.facts().get("savings_task.schedule.first_date").display());assertEquals("0",first.facts().get("savings_task.progress.verified_revocations").display());
  assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
  UUID id=UUID.fromString(first.data().path("id").asText());var workflow=app.getBean(WorkflowService.class);var w=workflow.get("u1",id);
  for(int i=0;i<3;i++){var action=(ActionVO)w.pendingAction();assertEquals("AWAITING_AUTH",action.status().name());authorize(app.getBean(ActionService.class).status("u1",action.id()));app.getBean(ActionExecutionService.class).submit("u1",action.id());w=workflow.resume("u1",id,w.version());}
  assertEquals("VERIFIED_SUCCESS",w.state());assertEquals(8300,w.estimatedSavingMinor());assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
  var status=caps.call(grant,previewRequest("savings-status","savings_task_status",Map.of("workflowId",id.toString())));assertEquals("VERIFIED_SUCCESS",status.data().path("state").asText());
  assertEquals("83.00元",status.facts().get("savings_task_status.verified_monthly_saving_minor").display());assertEquals("2",status.facts().get("savings_task_status.progress.verified_revocations").display());assertFalse(status.facts().containsKey("savings_task_status.pending.merchant"));
  assertThrows(BankException.class,()->workflow.get("u2",id));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM workflows WHERE id=?",Integer.class,id.toString()));
 }
 @Test void conversationalTransferPreviewIsIdempotentAndNeverAuthorizes(){
  String grant=token(contexts.create("u1"));var request=previewRequest("transfer-preview","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000));
  int ledgerBefore=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class),authBefore=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class);
  var first=caps.call(grant,request);var again=caps.call(grant,request);
  assertEquals("AWAITING_AUTH",first.data().path("pendingAction").path("status").asText());assertEquals(50000,first.data().path("pendingAction").path("amount_minor").asLong());
  assertEquals(first.data().path("pendingAction").path("id"),again.data().path("pendingAction").path("id"));assertTrue(again.replay());
  assertEquals(ledgerBefore,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));assertEquals(authBefore,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));
  assertTrue(first.facts().containsKey("preview_transfer.amount_minor"));
   assertTrue(first.facts().get("preview_transfer.recipient_note").display().contains("同学"));
   assertEquals("张三",first.facts().get("preview_transfer.recipient_label").display());
   assertTrue(first.data().path("pendingAction").path("recipient_label").asText().contains("TEST-"));
  var g=caps.authorize(grant,true);var result=new AgentResultDTO(g.getTurnId(),1,json.createObjectNode().put("kind","analysis").put("answer","待确认金额{{preview_transfer.amount_minor}}。尚未执行转账，请核对操作卡。"),List.of(first.evidenceId()));
  var response=app.getBean(GatewayResultService.class).complete(result).response();assertEquals(1,((List<?>)response.get("actions")).size());
 }
 @Test void ambiguousRecipientAndUnconfirmedMandateNeverCreatePreview(){
  String grant=token(contexts.create("u1"));int before=db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class);
  var ambiguous=caps.call(grant,previewRequest("ambiguous","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","张三","amountMinor",50000)));
  assertEquals("RECIPIENT_CLARIFICATION_REQUIRED",ambiguous.data().path("code").asText());assertFalse(ambiguous.data().has("pendingAction"));
  assertTrue(ambiguous.data().path("sourceResolved").asBoolean());assertEquals("AMBIGUOUS",ambiguous.data().path("recipientResolution").asText());
  assertEquals(2,ambiguous.data().path("candidates").size());assertTrue(ambiguous.facts().containsKey("recipients_latest.0.note"));
  assertFalse(ambiguous.data().path("candidates").get(0).has("id"));assertFalse(ambiguous.data().toString().contains("TEST-PHONE"));
  var turn=caps.authorize(grant,true);var results=app.getBean(GatewayResultService.class);
  var ack=results.complete(new AgentResultDTO(turn.getTurnId(),1,json.createObjectNode().put("kind","clarification").put("answer","付款账户已明确。请确认是备注{{recipients_latest.0.note}}，还是备注{{recipients_latest.1.note}}的收款人？尚未生成预览。"),List.of(ambiguous.evidenceId())));
  results.acknowledged(turn.getTurnId(),ack.resultHash(),ack.outputVersion());

  var unconfirmed=caps.call(token(contexts.create("u1")),previewRequest("unconfirmed-preview","revoke_preview",Map.of("merchantQuery","视频服务")));
  assertEquals("QUERY_CONFIRMATION_REQUIRED",unconfirmed.data().path("code").asText());assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
 }
 @Test void changedTransferInvalidatesOldPreviewAndSecondPreviewInSameTurnIsRejected(){
  String cid=contexts.create("u1"),grant=token(cid);var first=caps.call(grant,previewRequest("first","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000)));
  String old=first.data().path("pendingAction").path("id").asText();
  assertThrows(BankException.class,()->caps.call(grant,previewRequest("second","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",60000))));
  var g=caps.authorize(grant,true);var resultService=app.getBean(GatewayResultService.class);var ack=resultService.complete(new AgentResultDTO(g.getTurnId(),1,json.createObjectNode().put("kind","analysis").put("answer","待确认金额{{preview_transfer.amount_minor}}。尚未执行转账。"),List.of(first.evidenceId())));resultService.acknowledged(g.getTurnId(),ack.resultHash(),ack.outputVersion());
  var next=turns.begin("u1",new ChatRequestDTO("改成六百",null,cid,UUID.randomUUID().toString(),2L),NOW.plusSeconds(1800)).lease();String renewed=caps.issue(next.turnId());
  var second=caps.call(renewed,previewRequest("revised","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",60000)));
  assertEquals("CANCELLED",app.getBean(ActionService.class).status("u1",old).status().name());assertNotEquals(old,second.data().path("pendingAction").path("id").asText());
 }
 @Test void confirmedMandatePreviewUsesCurrentUserAndSnapshot(){
  String cid=contexts.create("u1");confirmed(cid);String grant=token(cid);
  var preview=caps.call(grant,previewRequest("video-preview","revoke_preview",Map.of("merchantQuery","视频服务")));
  assertEquals("REVOKE",preview.data().path("pendingAction").path("kind").asText());assertEquals("AWAITING_AUTH",preview.data().path("pendingAction").path("status").asText());assertEquals("ACTIVE",db.queryForObject("SELECT status FROM mandates WHERE id='video'",String.class));
  assertThrows(BankException.class,()->caps.call(grant,previewRequest("identity","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000,"userId","u2"))));
 }
 static void acknowledgePreview(String grant,cn.safebank.vo.AgentToolReceiptVO receipt){var g=caps.authorize(grant,true);var service=app.getBean(GatewayResultService.class);var ack=service.complete(new AgentResultDTO(g.getTurnId(),1,json.createObjectNode().put("kind","analysis").put("answer","待确认金额{{preview_transfer.amount_minor}}。尚未执行转账。"),List.of(receipt.evidenceId())));service.acknowledged(g.getTurnId(),ack.resultHash(),ack.outputVersion());}
 static String nextToken(String cid,String text){var lease=turns.begin("u1",new ChatRequestDTO(text,null,cid,UUID.randomUUID().toString(),2L),NOW.plusSeconds(1800)).lease();return caps.issue(lease.turnId());}
 @Test void recipientChangeToAmbiguousTargetCancelsPreviouslyAuthorizedCard(){
  String cid=contexts.create("u1"),grant=token(cid);var first=caps.call(grant,previewRequest("old-card","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000)));acknowledgePreview(grant,first);
  String old=first.data().path("pendingAction").path("id").asText();authorize(app.getBean(ActionService.class).status("u1",old));
  var notice=caps.call(nextToken(cid,"改给张三"),previewRequest("change-to-ambiguous","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","张三","amountMinor",50000)));
  assertEquals("RECIPIENT_CLARIFICATION_REQUIRED",notice.data().path("code").asText());assertEquals("CANCELLED",app.getBean(ActionService.class).status("u1",old).status().name());
  assertThrows(BankException.class,()->app.getBean(ActionExecutionService.class).submit("u1",old));
 }
 @Test void unknownOrPendingOriginalBlocksNewPreviewWithoutCreatingAnAction(){
  for(String state:List.of("UNKNOWN","PENDING")){
   String cid=contexts.create("u1"),grant=token(cid);var first=caps.call(grant,previewRequest("old-"+state,"transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000)));acknowledgePreview(grant,first);
   String old=first.data().path("pendingAction").path("id").asText();db.update("UPDATE actions SET status=? WHERE id=?",state,old);int count=db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class);
   String next=nextToken(cid,"改成六百");assertEquals("ACTION_RECONCILING",assertThrows(BankException.class,()->caps.call(next,previewRequest("blocked-"+state,"transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",60000)))).code);
   assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));assertEquals(state,app.getBean(ActionService.class).status("u1",old).status().name());
  }
 }
 @Test void completedTransferCannotBeModifiedIntoAnotherPayment(){
  String cid=contexts.create("u1"),grant=token(cid);var first=caps.call(grant,previewRequest("completed-original","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000)));acknowledgePreview(grant,first);
  String id=first.data().path("pendingAction").path("id").asText();authorize(app.getBean(ActionService.class).status("u1",id));app.getBean(ActionExecutionService.class).submit("u1",id);
  int count=db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
  String next=nextToken(cid,"改成六百");assertEquals("ACTION_ALREADY_COMPLETED",assertThrows(BankException.class,()->caps.call(next,previewRequest("completed-revision","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",60000)))).code);
  assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
 }
 @Test void concurrentCancellationCannotPassPreviewFactsUsingAnOldRrSnapshot(){
  String grant=token(contexts.create("u1"));var receipt=caps.call(grant,previewRequest("rr-preview","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery","同学","amountMinor",50000)));
  String id=receipt.data().path("pendingAction").path("id").asText();var transaction=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  transaction.executeWithoutResult(s->{
   db.queryForObject("SELECT status FROM actions WHERE id=?",String.class,id);
   try{java.util.concurrent.CompletableFuture.runAsync(()->app.getBean(ActionService.class).cancel("u1",id)).get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
   assertEquals("PREVIEW_STALE",assertThrows(BankException.class,()->app.getBean(AgentPreviewService.class).facts("u1",receipt.data())).code);
  });
 }
 static ActionVO authorize(ActionVO a){return app.getBean(ActionService.class).authorize("u1",a.id(),new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),true,null));}
 @Test void completingMandateAnalysisAndExecutingRevokeDoNotDeadlock() throws Exception {
  String cid=contexts.create("u1");confirmed(cid);String grant=token(cid);var preview=caps.call(grant,previewRequest("revoke-race","revoke_preview",Map.of("merchantQuery","视频服务")));
  var g=caps.authorize(grant,true);var service=app.getBean(GatewayResultService.class);var ack=service.complete(new AgentResultDTO(g.getTurnId(),1,json.createObjectNode().put("kind","analysis").put("answer","待确认对象{{preview_revoke.merchant}}。尚未执行撤销。"),List.of(preview.evidenceId())));service.acknowledged(g.getTurnId(),ack.resultHash(),ack.outputVersion());
  String action=preview.data().path("pendingAction").path("id").asText();authorize(app.getBean(ActionService.class).status("u1",action));
  String next=nextToken(cid,"当前代扣名单" );var receipt=caps.call(next,previewRequest("read-race","mandate_receipt",Map.of()));var turn=caps.authorize(next,true);
  var answer=new AgentResultDTO(turn.getTurnId(),2,json.createObjectNode().put("kind","analysis").put("answer","查询时月付合计{{mandates_summary.known_monthly_total_minor}}。"),List.of(receipt.evidenceId()));
  var ready=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   var complete=executor.submit(()->{ready.await();try{service.complete(answer);return "COMPLETED";}catch(BankException e){return e.code;}});
   var execute=executor.submit(()->{ready.await();return app.getBean(ActionExecutionService.class).submit("u1",action);});ready.countDown();
   assertTrue(Set.of("COMPLETED","QUERY_CONFIRMATION_REQUIRED").contains(complete.get(15,java.util.concurrent.TimeUnit.SECONDS)));execute.get(15,java.util.concurrent.TimeUnit.SECONDS);
   assertEquals("VERIFIED_SUCCESS",app.getBean(ActionService.class).status("u1",action).status().name());
  }finally{executor.shutdownNow();}
 }
 static ActionVO confirmed(String cid){var a=queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,1L);authorize(a);queries.executeAuthorized("u1",a.id());return a;}
 static String token(String cid){var lease=turns.begin("u1",new ChatRequestDTO("查询已确认代扣",null,cid,UUID.randomUUID().toString(),1L),NOW.plusSeconds(1800)).lease();return caps.issue(lease.turnId());}
}
