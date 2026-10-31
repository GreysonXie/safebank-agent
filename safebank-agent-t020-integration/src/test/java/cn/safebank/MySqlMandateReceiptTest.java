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
class MySqlMandateReceiptTest {
 static final Instant NOW=Instant.parse("2026-10-11T02:00:00Z");static ConfigurableApplicationContext app;static JdbcTemplate db;
 static ContextService contexts;static RiskQueryService queries;static AgentCapabilityService caps;static GatewayTurnService turns;static ObjectMapper json;static MutableRiskClock clock;
 @org.springframework.boot.test.context.TestConfiguration static class TimeConfig {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary MutableRiskClock testClock(){return new MutableRiskClock(NOW);}
 }
 @BeforeAll static void start(){var ds=(DriverManagerDataSource)MySqlTestSupport.fresh("receipt");app=new SpringApplicationBuilder(Application.class,TimeConfig.class).profiles("mysql","mysql-test","agent-gateway","langgraph-agent").run("--server.port=0","--spring.datasource.url="+ds.getUrl(),"--bank.agent.service-key="+"x".repeat(64),"--bank.agent.reconcile.enabled=false","--bank.model.debug-budget.path="+System.getProperty("java.io.tmpdir")+"/mandate-receipt-budget-"+UUID.randomUUID()+".json","--logging.level.root=ERROR","--spring.main.banner-mode=off");db=new JdbcTemplate(ds);new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/mysql/v07-synthetic.sql")).execute(ds);contexts=app.getBean(ContextService.class);queries=app.getBean(RiskQueryService.class);caps=app.getBean(AgentCapabilityService.class);turns=app.getBean(GatewayTurnService.class);json=app.getBean(ObjectMapper.class);clock=app.getBean(MutableRiskClock.class);}
 @AfterAll static void close(){if(app!=null)app.close();}
 @BeforeEach void resetClock(){clock.set(NOW);db.update("UPDATE mandates SET status='ACTIVE',amount_minor=1500 WHERE id='music'");}
 static AgentToolRequestDTO request(String key){var args=json.createObjectNode();return new AgentToolRequestDTO(ContextEvidenceService.hash(key),caps.inputHash("mandate_receipt",args),"mandate_receipt",args);}
 static ActionVO authorize(ActionVO a){return app.getBean(ActionService.class).authorize("u1",a.id(),new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),true,null));}
 static ActionVO confirmed(String cid){var a=queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,1L);authorize(a);queries.executeAuthorized("u1",a.id());return a;}
 static String token(String cid){var lease=turns.begin("u1",new ChatRequestDTO("查询已确认代扣",null,cid,UUID.randomUUID().toString(),1L),NOW.plusSeconds(1800)).lease();return caps.issue(lease.turnId());}
 @Test void unconfirmedReceiptHasSafeCodeAndNoFacts(){var result=assertDoesNotThrow(()->caps.call(token(contexts.create("u1")),request("unconfirmed")));assertEquals("QUERY_CONFIRMATION_REQUIRED",result.data().path("code").asText());assertTrue(result.facts().isEmpty());assertFalse(result.data().has("mandates"));}
 @Test void pendingActionAndLegacyUnboundQueryNeverShareData(){String cid=contexts.create("u1");queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,1L);var legacy=queries.prepareMandates("u1",RiskChannel.HTTP);authorize(legacy);queries.executeAuthorized("u1",legacy.id());var result=caps.call(token(cid),request("pending"));assertTrue(result.facts().isEmpty());assertEquals("QUERY_CONFIRMATION_REQUIRED",result.data().path("code").asText());}
 @Test void bindingRejectsWrongOwnerVersionAndProcessingTurnWithoutNewAction(){String cid=contexts.create("u1");int before=db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class);assertEquals("NOT_FOUND",assertThrows(BankException.class,()->queries.prepareMandatesBound("u2",RiskChannel.HTTP,cid,1L)).code);assertThrows(BankException.class,()->queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,2L));assertThrows(BankException.class,()->queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,null));token(cid);assertThrows(BankException.class,()->queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,1L));assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));}
 @Test void confirmationFreezesDataExpiryAndExistingMonthlyFacts(){String cid=contexts.create("u1");var a=confirmed(cid);db.update("UPDATE mandates SET amount_minor=999999 WHERE id='music'");clock.set(NOW.plusSeconds(30));var repeated=queries.executeAuthorized("u1",a.id());assertNull(repeated.data());assertNull(repeated.queriedAt());var result=caps.call(token(cid),request("frozen"));assertEquals(1500,java.util.stream.StreamSupport.stream(result.data().spliterator(),false).filter(m->m.path("id").asText().equals("music")).findFirst().orElseThrow().path("amount_minor").asLong());assertEquals(NOW,result.observedAt());assertEquals(NOW,result.sourceQueriedAt());assertEquals(NOW.plusSeconds(300),result.sourceExpiresAt());assertTrue(result.facts().containsKey("mandates_summary.known_monthly_total_minor"));assertEquals(8300,result.facts().get("mandates_summary.known_monthly_total_minor").number().longValueExact());assertTrue(result.facts().get("mandates_summary.known_monthly_total_minor").source().contains(NOW.toString()));var isolated=caps.call(token(contexts.create("u1")),request("isolated"));assertTrue(isolated.facts().isEmpty());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));}
 @Test void expiryRejectsOldOperationReplayAndAnswerValidation(){String cid=contexts.create("u1");confirmed(cid);String grant=token(cid);var operation=request("expires");var result=caps.call(grant,operation);clock.set(NOW.plusSeconds(300));String renewed=caps.renew(caps.originalIdentity(grant).getTurnId(),grant);assertEquals("QUERY_CONFIRMATION_REQUIRED",assertThrows(BankException.class,()->caps.call(renewed,operation)).code);var g=caps.authorize(renewed,true);var answer=new AgentResultDTO(g.getTurnId(),1L,json.createObjectNode().put("kind","analysis").put("answer","合计{{mandates_summary.known_monthly_total_minor}}。"),List.of(result.evidenceId()));assertEquals("QUERY_CONFIRMATION_REQUIRED",assertThrows(BankException.class,()->app.getBean(GatewayResultService.class).validate(renewed,answer)).code);assertTrue(caps.call(renewed,request("afterexpiry")).facts().isEmpty());}
 @Test void resetInvalidatesPendingAndConfirmedBindings(){String cid=contexts.create("u1");var a=queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,1L);contexts.reset("u1",cid,1L);authorize(a);queries.executeAuthorized("u1",a.id());assertNull(db.queryForObject("SELECT snapshot_json FROM agent_mandate_query_receipts WHERE action_id=?",String.class,a.id()));String c2=contexts.create("u1");confirmed(c2);contexts.reset("u1",c2,1L);assertEquals(1,db.queryForObject("SELECT invalidated FROM agent_mandate_query_receipts WHERE conversation_id=?",Integer.class,c2));}
 static void afterConcurrentInvalidation(Runnable check){
  var manager=app.getBean(org.springframework.transaction.PlatformTransactionManager.class);
  var outer=new org.springframework.transaction.support.TransactionTemplate(manager);
  outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  outer.executeWithoutResult(status->{
   new JdbcTemplate(app.getBean(javax.sql.DataSource.class)).queryForObject("SELECT COUNT(*) FROM agent_mandate_query_receipts",Integer.class);
   var concurrent=new org.springframework.transaction.support.TransactionTemplate(manager);
   concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
   concurrent.executeWithoutResult(inner->app.getBean(AgentMandateReceiptService.class).invalidateUser("u1"));
   try{check.run();}finally{status.setRollbackOnly();}
  });
 }
 @Test void repeatableReadMustNotResurrectInvalidatedLatestSnapshot(){
  String cid=contexts.create("u1");confirmed(cid);String grant=token(cid);
  afterConcurrentInvalidation(()->assertTrue(caps.call(grant,request("rr-latest")).facts().isEmpty()));
 }
 @Test void repeatableReadRejectsInvalidatedCachedToolAndFinalAnswer(){
  String cid=contexts.create("u1");confirmed(cid);String grant=token(cid);var operation=request("rr-replay");var receipt=caps.call(grant,operation);
  var identity=caps.originalIdentity(grant);
  var answer=new AgentResultDTO(identity.getTurnId(),1L,json.createObjectNode().put("kind","analysis").put("answer","合计{{mandates_summary.known_monthly_total_minor}}。"),List.of(receipt.evidenceId()));
  afterConcurrentInvalidation(()->{
   assertEquals("QUERY_CONFIRMATION_REQUIRED",assertThrows(BankException.class,()->caps.call(grant,operation)).code);
   assertEquals("QUERY_CONFIRMATION_REQUIRED",assertThrows(BankException.class,()->app.getBean(GatewayResultService.class).validate(grant,answer)).code);
  });
 }
 @Test void revokeInvalidatesAllUserReceiptsAndCachedEvidence(){String cid=contexts.create("u1"),cid2=contexts.create("u1");confirmed(cid);confirmed(cid2);String grant=token(cid);var operation=request("revoke");var result=caps.call(grant,operation);var actions=app.getBean(ActionService.class);var revoke=actions.previewRevoke("u1",new MandatePreviewRequestDTO("music"));authorize(revoke);app.getBean(ActionExecutionService.class).submit("u1",revoke.id());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_mandate_query_receipts WHERE user_id='u1' AND invalidated=FALSE",Integer.class));assertEquals("QUERY_CONFIRMATION_REQUIRED",assertThrows(BankException.class,()->caps.call(grant,operation)).code);var identity=caps.authorize(grant,true);var answer=new AgentResultDTO(identity.getTurnId(),1L,json.createObjectNode().put("kind","analysis").put("answer","合计{{mandates_summary.known_monthly_total_minor}}。"),List.of(result.evidenceId()));assertThrows(BankException.class,()->app.getBean(GatewayResultService.class).validate(grant,answer));}
 @Test void snapshotWriteFailureRollsBackQueryCompletionAndConsumption(){String cid=contexts.create("u1");var a=queries.prepareMandatesBound("u1",RiskChannel.HTTP,cid,1L);authorize(a);db.execute("CREATE TRIGGER reject_mandate_snapshot BEFORE UPDATE ON agent_mandate_query_receipts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test receipt failure'");try{assertThrows(Exception.class,()->queries.executeAuthorized("u1",a.id()));}finally{db.execute("DROP TRIGGER reject_mandate_snapshot");}assertEquals("AUTHORIZED",db.queryForObject("SELECT status FROM actions WHERE id=?",String.class,a.id()));assertNull(db.queryForObject("SELECT snapshot_json FROM agent_mandate_query_receipts WHERE action_id=?",String.class,a.id()));assertNotNull(queries.executeAuthorized("u1",a.id()).queriedAt());}
 @Test void hashTamperingAndForeignKeysRejectUntrustedReceipt(){String cid=contexts.create("u1");var a=confirmed(cid);db.update("UPDATE agent_mandate_query_receipts SET snapshot_json='[]' WHERE action_id=?",a.id());assertEquals("CONFLICT",assertThrows(BankException.class,()->caps.call(token(cid),request("tamper"))).code);String other=contexts.create("u2");assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO agent_mandate_query_receipts(action_id,user_id,conversation_id,bound_version) VALUES(?,?,?,1)",queries.prepareMandates("u1",RiskChannel.HTTP).id(),"u2",other));var unbound=queries.prepareMandates("u1",RiskChannel.HTTP);assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO agent_mandate_query_receipts(action_id,user_id,conversation_id,bound_version) VALUES(?,?,?,1)",unbound.id(),"u1",other));}
}
