package cn.safebank;
import cn.safebank.common.ContextContracts.*;
import cn.safebank.dto.*;
import cn.safebank.service.*;
import cn.safebank.dao.AgentGatewayMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlAgentGatewayTest {
    static ConfigurableApplicationContext app;static DriverManagerDataSource ds;static JdbcTemplate db;
    static GatewayTurnService turns;static AgentCapabilityService capabilities;static ContextService contexts;static AgentGatewayMapper mapper;static ObjectMapper json;static MutableRiskClock clock;
    static com.sun.net.httpserver.HttpServer fake;
    static final java.util.concurrent.ConcurrentHashMap<String,com.fasterxml.jackson.databind.JsonNode> runs=new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger();
    static String fault="NONE";
    static final Instant NOW=Instant.parse("2026-10-10T04:00:00Z");
    @org.springframework.boot.test.context.TestConfiguration static class TimeConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary MutableRiskClock testClock(){return new MutableRiskClock(NOW);}
    }
    @BeforeAll static void start() {
        try{fake=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);fake.createContext("/internal/agent/turns",exchange->{
            var codec=new ObjectMapper();var path=exchange.getRequestURI().getPath();var method=exchange.getRequestMethod();int code=200;com.fasterxml.jackson.databind.JsonNode reply;
            if(fault.equals("DOWN")){code=503;reply=codec.createObjectNode();}
            else if(method.equals("GET")){String id=path.substring(path.lastIndexOf('/')+1);reply=runs.getOrDefault(id,codec.createObjectNode().put("state","NO_RUN"));}
            else {var b=codec.readTree(exchange.getRequestBody());String id=b.path("turnId").asText();
                if(path.endsWith("/ack")){var saved=(com.fasterxml.jackson.databind.node.ObjectNode)runs.get(id);saved.put("state","COMPLETED").put("resultHash",b.path("resultHash").asText()).put("outputVersion",b.path("outputVersion").asLong());reply=saved;if(fault.equals("DROP_ACK"))code=503;}
                else {starts.incrementAndGet();var result=codec.createObjectNode().put("turnId",id).put("inputVersion",b.path("expectedVersion").asLong());result.set("answer",codec.createObjectNode().put("kind","clarification").put("answer","请提供月份。"));result.set("evidenceIds",codec.createArrayNode());reply=codec.createObjectNode().put("state","RESULT_READY").set("result",result);runs.put(id,reply);if(fault.equals("DROP_START"))code=503;}
            }
            byte[] bytes=codec.writeValueAsBytes(reply);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });fake.start();}catch(java.io.IOException e){throw new IllegalStateException(e);}
        ds=(DriverManagerDataSource)MySqlTestSupport.fresh("gateway");
        app=new SpringApplicationBuilder(Application.class,TimeConfig.class).profiles("mysql","mysql-test","agent-gateway")
            .run("--server.port=0","--spring.datasource.url="+ds.getUrl(),"--bank.agent.service-key="+"x".repeat(64),"--bank.agent.reconcile.enabled=false","--bank.agent.base-url=http://127.0.0.1:"+fake.getAddress().getPort(),"--logging.level.root=ERROR","--spring.main.banner-mode=off");
        db=new JdbcTemplate(ds);new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/mysql/v07-synthetic.sql")).execute(ds);
        turns=app.getBean(GatewayTurnService.class);capabilities=app.getBean(AgentCapabilityService.class);contexts=app.getBean(ContextService.class);mapper=app.getBean(AgentGatewayMapper.class);json=app.getBean(ObjectMapper.class);clock=app.getBean(MutableRiskClock.class);
    }
    @AfterAll static void close(){if(app!=null)app.close();if(fake!=null)fake.stop(0);}
    @BeforeEach void time(){clock.set(NOW);fault="NONE";runs.clear();starts.set(0);}
    static TurnLease lease(){return turns.begin("u1",new ChatRequestDTO("测试只读网关",null),NOW.plusSeconds(1800)).lease();}
    @Test void statusProjectionIsOwnedReadonlyAndPreservesPausedState(){
        var l=lease();var status=app.getBean(AgentTurnStatusService.class);
        int auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
        var first=status.status("u1",l.turnId());assertEquals("PENDING",first.state());assertFalse(first.requiresReview());assertEquals(0,first.outputVersion());
        assertEquals("NOT_FOUND",assertThrows(BankException.class,()->status.status("u2",l.turnId())).code);
        app.getBean(GatewayResultService.class).pause(l.turnId());assertTrue(status.status("u1",l.turnId()).requiresReview());
        assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));assertEquals(0,starts.get());
    }
    @Test void retryReadsReadyOriginalTurnWithoutPostingAgain(){
        String cid=contexts.create("u1");var request=new ChatRequestDTO("待恢复查询",null,cid,"ready-retry",1L);var l=turns.begin("u1",request,NOW.plusSeconds(300)).lease();
        var result=json.createObjectNode().put("turnId",l.turnId()).put("inputVersion",1);result.set("answer",json.createObjectNode().put("kind","clarification").put("answer","请提供月份。"));result.set("evidenceIds",json.createArrayNode());runs.put(l.turnId(),json.createObjectNode().put("state","RESULT_READY").set("result",result));
        var session=new org.springframework.mock.web.MockHttpSession();session.setMaxInactiveInterval(1800);
        var response=app.getBean(cn.safebank.agent.GatewayCoordinator.class).chat("u1",request,session);
        assertEquals(2L,((Number)response.get("contextVersion")).longValue());assertEquals(0,starts.get());assertEquals("ACKED",mapper.gateway(l.turnId()).getState());
    }
    @Test void pausedOriginalRecoveryHintDoesNotStartOrRenewCapability(){
        String cid=contexts.create("u1");var request=new ChatRequestDTO("原查询",null,cid,"paused-recovery",1L);
        var l=turns.begin("u1",request,NOW.plusSeconds(300)).lease();
        var token=capabilities.issue(l.turnId());app.getBean(GatewayResultService.class).pause(l.turnId());
        runs.put(l.turnId(),json.createObjectNode().put("state","RECOVERY_REQUIRED"));
        int grants=db.queryForObject("SELECT COUNT(*) FROM agent_service_grants WHERE turn_id=?",Integer.class,l.turnId());
        int ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
        var session=new org.springframework.mock.web.MockHttpSession();session.setMaxInactiveInterval(1800);
        assertEquals("AGENT_REVIEW_REQUIRED",assertThrows(BankException.class,()->app.getBean(cn.safebank.agent.GatewayCoordinator.class).chat("u1",request,session)).code);
        assertEquals("PAUSED",mapper.gateway(l.turnId()).getState());assertEquals(0,starts.get());
        // The scheduler visits PAUSED too; it must obey the same no-recovery boundary.
        reconciler().reconcile(l.turnId());
        assertEquals("PAUSED",mapper.gateway(l.turnId()).getState());assertEquals(0,starts.get());
        assertEquals(grants,db.queryForObject("SELECT COUNT(*) FROM agent_service_grants WHERE turn_id=?",Integer.class,l.turnId()));
        assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
        assertEquals(0,mapper.receipts(l.turnId()).size());assertEquals(1,contexts.snapshot("u1",cid).version());
    }
    @Test void originalSavedReplyRepairsAckAndUnblocksNextTurnWithoutNewStart(){
        String cid=contexts.create("u1");var request=new ChatRequestDTO("原查询",null,cid,"paused-ack",1L);
        var session=new org.springframework.mock.web.MockHttpSession();session.setMaxInactiveInterval(1800);fault="DROP_ACK";
        var coordinator=app.getBean(cn.safebank.agent.GatewayCoordinator.class);
        var first=coordinator.chat("u1",request,session);var g=turns.original("u1",cid,"paused-ack");
        app.getBean(GatewayResultService.class).pause(g.getTurnId());fault="NONE";
        var replay=coordinator.chat("u1",request,session);
        assertEquals(first,replay);assertEquals("ACKED",mapper.gateway(g.getTurnId()).getState());
        assertEquals(1,starts.get());assertEquals(2,contexts.snapshot("u1",cid).version());
        assertNotNull(turns.begin("u1",new ChatRequestDTO("下一轮",null,cid,"after-ack",2L),NOW.plusSeconds(300)).lease());
        assertEquals(0,mapper.receipts(g.getTurnId()).size());
    }
    @Test void missingRemoteRunDoesNotInterruptPausedOriginal(){
        var l=lease();app.getBean(GatewayResultService.class).pause(l.turnId());
        reconciler().reconcile(l.turnId());
        assertEquals("PAUSED",mapper.gateway(l.turnId()).getState());
        assertEquals("PROCESSING",db.queryForObject("SELECT state FROM agent_turn_receipts WHERE id=?",String.class,l.turnId()));
        assertEquals(1,contexts.snapshot("u1",l.conversationId()).version());assertEquals(0,starts.get());
    }
    @Test void pausedOriginalCannotMintOrRenewCapability(){
        var l=lease();String token=capabilities.issue(l.turnId());
        app.getBean(GatewayResultService.class).pause(l.turnId());
        int before=db.queryForObject("SELECT COUNT(*) FROM agent_service_grants WHERE turn_id=?",Integer.class,l.turnId());
        assertEquals("AGENT_REVIEW_REQUIRED",assertThrows(BankException.class,()->capabilities.issue(l.turnId())).code);
        assertEquals("AGENT_REVIEW_REQUIRED",assertThrows(BankException.class,()->capabilities.renew(l.turnId(),token)).code);
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM agent_service_grants WHERE turn_id=?",Integer.class,l.turnId()));
        assertEquals("ACTIVE",mapper.grant(ContextEvidenceService.hash(token)).getState());
        assertEquals(0,mapper.receipts(l.turnId()).size());assertEquals(0,starts.get());
    }
    static AgentToolRequestDTO request(String key,String tool,String args)throws Exception{var a=json.readTree(args);return new AgentToolRequestDTO(ContextEvidenceService.hash(key),capabilities.inputHash(tool,a),tool,a);}
    @Test void externalRequestIdentityAndVersionRemainAuthoritative(){
        String c=contexts.create("u1");var r=new ChatRequestDTO("查询",null,c,"req1",1L);var one=turns.begin("u1",r,NOW.plusSeconds(300));
        assertTrue(turns.begin("u1",r,NOW.plusSeconds(300)).processing());
        assertThrows(BankException.class,()->turns.begin("u1",new ChatRequestDTO("不同",null,c,"req1",1L),NOW.plusSeconds(300)));
        assertThrows(BankException.class,()->turns.begin("u2",r,NOW.plusSeconds(300)));
        assertThrows(BankException.class,()->turns.begin("u1",new ChatRequestDTO("查询",null,c,"req2",99L),NOW.plusSeconds(300)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_gateway_turns WHERE turn_id=?",Integer.class,one.lease().turnId()));
    }
    @Test void replayIsOriginalSnapshotAndWrongHashCannotConsumeQuota()throws Exception{
        var l=lease();var token=capabilities.issue(l.turnId());var r=request("same","statement","{\"month\":\"2026-09\"}");var first=capabilities.call(token,r);
        assertEquals(536933,first.data().path("expense_minor").asLong());var again=capabilities.call(token,r);assertTrue(again.replay());assertEquals(first.observedAt(),again.observedAt());assertEquals(first.resultHash(),again.resultHash());
        assertThrows(BankException.class,()->capabilities.call(token,request("same","statement","{\"month\":\"2026-08\"}")));
        assertThrows(BankException.class,()->capabilities.call(token,new AgentToolRequestDTO(r.operationKey(),"0".repeat(64),r.tool(),r.args())));
        assertEquals(1,mapper.receipts(l.turnId()).size());
    }
    @Test void comparisonFactsKeepBothMonthsAndRenderBothAmounts()throws Exception{
        var l=lease();var token=capabilities.issue(l.turnId());var receipt=capabilities.call(token,request("comparison","statement_compare","{\"month\":\"2026-09\",\"comparisonMonth\":\"2026-08\"}"));
        assertTrue(receipt.facts().containsKey("statement_2026_09.expense_minor"));assertTrue(receipt.facts().containsKey("statement_2026_08.expense_minor"));
        assertEquals(536933,receipt.facts().get("statement_2026_09.expense_minor").number().longValueExact());
        var answer=result(l,"两个月支出分别为{{statement_2026_09.expense_minor}}和{{statement_2026_08.expense_minor}}。","analysis",List.of(receipt.evidenceId()));
        var ack=app.getBean(GatewayResultService.class).complete(answer);assertEquals(2,((List<?>)ack.response().get("results")).size());
    }
    @Test void renewalDoesNotResetEightCallLimitAndRevokesOldToken()throws Exception{
        var l=lease();var old=capabilities.issue(l.turnId());for(int i=0;i<4;i++)capabilities.call(old,request("first"+i,"account_query","{}"));
        var current=capabilities.issue(l.turnId());assertThrows(BankException.class,()->capabilities.call(old,request("revoked","account_query","{}")));
        for(int i=0;i<4;i++)capabilities.call(current,request("second"+i,"account_query","{}"));
        var e=assertThrows(BankException.class,()->capabilities.call(current,request("ninth","account_query","{}")));assertEquals("TOOL_QUOTA_EXCEEDED",e.code);assertEquals(8,mapper.receipts(l.turnId()).size());
    }
    @Test void invalidQueriesAndFundingToolsLeaveNoReceipt()throws Exception{
        var l=lease();var token=capabilities.issue(l.turnId());
        for(var args:List.of("{\"month\":\"2026-13\"}","{\"month\":\"2026-09\",\"userId\":\"u2\"}"))assertThrows(BankException.class,()->capabilities.call(token,request(args,"statement",args)));
        assertThrows(BankException.class,()->capabilities.call(token,request("submit","submit","{}")));
        assertEquals(0,mapper.receipts(l.turnId()).size());assertEquals(0,db.queryForObject("SELECT SUM(calls_used) FROM agent_service_grants WHERE turn_id=?",Integer.class,l.turnId()));
    }
    @Test void grantStrictExpiryAndSessionUpperBound()throws Exception{
        var l=turns.begin("u1",new ChatRequestDTO("短会话",null),NOW.plusSeconds(20)).lease();var token=capabilities.issue(l.turnId());
        var g=mapper.grant(ContextEvidenceService.hash(token));assertEquals(NOW.plusSeconds(20),g.getExpiresAt().toInstant());assertNotEquals(token,g.getTokenHash());
        clock.set(NOW.plusSeconds(20));assertThrows(BankException.class,()->capabilities.call(token,request("expiry","account_query","{}")));assertThrows(BankException.class,()->capabilities.issue(l.turnId()));
    }
    @Test void simultaneousRenewalLeavesOnlyOneActiveGrant()throws Exception{
        var l=lease();try(var pool=Executors.newFixedThreadPool(2)){
            var go=new CountDownLatch(1);var one=pool.submit(()->{go.await();return capabilities.issue(l.turnId());});var two=pool.submit(()->{go.await();return capabilities.issue(l.turnId());});go.countDown();assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_service_grants WHERE turn_id=? AND state='ACTIVE'",Integer.class,l.turnId()));
    }
    @Test void simultaneousSameKeyQueriesCommitOneReceipt()throws Exception{
        var l=lease();var token=capabilities.issue(l.turnId());var r=request("parallel","account_query","{}");
        try(var pool=Executors.newFixedThreadPool(2)){
            var go=new CountDownLatch(1);var one=pool.submit(()->{go.await();return capabilities.call(token,r);});var two=pool.submit(()->{go.await();return capabilities.call(token,r);});go.countDown();assertEquals(one.get(10,TimeUnit.SECONDS).evidenceId(),two.get(10,TimeUnit.SECONDS).evidenceId());
        }assertEquals(1,mapper.receipts(l.turnId()).size());
    }
    static AgentResultDTO result(TurnLease l,String text,String kind,List<String> evidence){return new AgentResultDTO(l.turnId(),l.inputVersion(),json.createObjectNode().put("answer",text).put("kind",kind),evidence);}
    @Test void completionAckIsIdempotentEvenAfterNextTurnStarts(){
        var l=lease();var r=result(l,"请说明要查询的月份。","clarification",List.of());var service=app.getBean(GatewayResultService.class);
        var ack=assertDoesNotThrow(()->service.complete(r));assertEquals(2,ack.outputVersion());assertEquals(2,contexts.snapshot("u1",l.conversationId()).version());
        assertThrows(BankException.class,()->turns.begin("u1",new ChatRequestDTO("下一轮",null,l.conversationId(),"next",2L),NOW.plusSeconds(300)));
        service.acknowledged(l.turnId(),ack.resultHash(),ack.outputVersion());
        turns.begin("u1",new ChatRequestDTO("下一轮",null,l.conversationId(),"next",2L),NOW.plusSeconds(300));
        assertEquals(ack,assertDoesNotThrow(()->service.complete(r)));assertEquals(2,contexts.snapshot("u1",l.conversationId()).version());
        var e=assertThrows(BankException.class,()->service.complete(result(l,"不同回答。","clarification",List.of())));assertEquals("CONFLICT",e.code);
    }
    @Test void groundedCompletionRejectsUnregisteredEvidenceAndHandwrittenMoney()throws Exception{
        var l=lease();var token=capabilities.issue(l.turnId());var receipt=capabilities.call(token,request("ground","statement","{\"month\":\"2026-09\"}"));var service=app.getBean(GatewayResultService.class);
        var bad=assertThrows(BankException.class,()->service.complete(result(l,"支出为9999元。","analysis",List.of(receipt.evidenceId()))));assertNotEquals("NOT_IMPLEMENTED",bad.code);
        assertThrows(BankException.class,()->service.complete(result(l,"请说明月份。","clarification",List.of("other-turn"))));assertEquals(1,contexts.snapshot("u1",l.conversationId()).version());
        var fact=receipt.facts().keySet().stream().filter(k->k.endsWith("expense_minor")).findFirst().orElseThrow();
        var ack=assertDoesNotThrow(()->service.complete(result(l,"本月支出为{{"+fact+"}}。","analysis",List.of(receipt.evidenceId()))));assertTrue(ack.response().get("text").toString().replace(",","").contains("5369.33"),ack.response().get("text").toString());assertEquals(1,((List<?>)ack.response().get("results")).size());assertEquals(536933,json.valueToTree(ack.response().get("data")).path("expense_minor").asLong());
    }
    GatewayReconciler reconciler(){var reconciler=new GatewayReconciler(mapper,turns,app.getBean(GatewayResultService.class),app.getBean(cn.safebank.agent.HttpAgentClient.class));org.springframework.test.util.ReflectionTestUtils.setField(reconciler,"capabilities",capabilities);return reconciler;}
    @Test void lostStartResponseReconcilesOriginalRunWithoutSecondPost(){
        String c=contexts.create("u1");var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("user","u1");fault="DROP_START";
        var e=assertThrows(BankException.class,()->app.getBean(cn.safebank.agent.GatewayCoordinator.class).chat("u1",new ChatRequestDTO("查询",null,c,"lost",1L),session));assertEquals("AGENT_RECONCILING",e.code);
        String id=db.queryForObject("SELECT turn_id FROM agent_gateway_turns WHERE conversation_id=?",String.class,c);fault="NONE";reconciler().reconcile(id);
        assertEquals("ACKED",mapper.gateway(id).getState());assertEquals(2,contexts.snapshot("u1",c).version());assertEquals(1,starts.get());
    }
    @Test void lostAckResponseReusesCommittedJavaVersion(){
        String c=contexts.create("u1");var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("user","u1");fault="DROP_ACK";
        var reply=app.getBean(cn.safebank.agent.GatewayCoordinator.class).chat("u1",new ChatRequestDTO("查询",null,c,"acklost",1L),session);assertEquals(2L,((Number)reply.get("contextVersion")).longValue());
        String id=db.queryForObject("SELECT turn_id FROM agent_gateway_turns WHERE conversation_id=?",String.class,c);assertEquals("ACK_PENDING",mapper.gateway(id).getState());fault="NONE";reconciler().reconcile(id);
        assertEquals("ACKED",mapper.gateway(id).getState());assertEquals(2,contexts.snapshot("u1",c).version());assertEquals(1,starts.get());
    }
    @Test void unavailablePausesAfterThreeAndRecoveryUsesSavedRun(){
        var l=lease();var r=result(l,"请说明月份。","clarification",List.of());runs.put(l.turnId(),json.createObjectNode().put("state","RESULT_READY").set("result",json.valueToTree(r)));fault="DOWN";
        for(int i=0;i<3;i++)reconciler().reconcile(l.turnId());assertEquals("PAUSED",mapper.gateway(l.turnId()).getState());assertEquals(3,mapper.gateway(l.turnId()).getReconcileFailures());assertEquals(1,contexts.snapshot("u1",l.conversationId()).version());
        fault="NONE";reconciler().reconcile(l.turnId());assertEquals("ACKED",mapper.gateway(l.turnId()).getState());assertEquals(2,contexts.snapshot("u1",l.conversationId()).version());assertEquals(0,starts.get());
    }
    @Test void confirmedNoRunInterruptsWithoutCreatingAnotherRun(){
        var l=lease();reconciler().reconcile(l.turnId());assertEquals("INTERRUPTED",mapper.gateway(l.turnId()).getState());assertEquals("INTERRUPTED",db.queryForObject("SELECT state FROM agent_turn_receipts WHERE id=?",String.class,l.turnId()));assertEquals(0,starts.get());
    }
    @Test void internalToolsRequireServiceIdentityAndGrantEvenWithBrowserCookie()throws Exception{
        int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)app).getWebServer().getPort();String base="http://127.0.0.1:"+port;
        var http=java.net.http.HttpClient.newHttpClient();var cookie=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/session")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        var l=lease();var token=capabilities.issue(l.turnId());String body=json.writeValueAsString(request("http","account_query","{}"));
        var noIdentity=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/internal/agent/tools")).header("Content-Type","application/json").header("Cookie",cookie).header("X-Agent-Grant",token).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403,http.send(noIdentity,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var noGrant=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/internal/agent/tools")).header("Content-Type","application/json").header("Authorization","Bearer "+"x".repeat(64)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(400,http.send(noGrant,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var valid=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/internal/agent/tools")).header("Content-Type","application/json").header("Authorization","Bearer "+"x".repeat(64)).header("X-Agent-Grant",token).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var reply=http.send(valid,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,reply.statusCode());assertEquals(2,json.readTree(reply.body()).path("data").size());assertFalse(reply.body().contains(token));
    }

    @Test void failedGrantReplacementRestoresOriginalActiveToken(){
        var l=lease();var token=capabilities.issue(l.turnId());db.execute("CREATE TRIGGER reject_gateway_grant BEFORE INSERT ON agent_service_grants FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test insertion fault'");
        try{assertThrows(Exception.class,()->capabilities.issue(l.turnId()));}finally{db.execute("DROP TRIGGER reject_gateway_grant");}
        assertEquals("ACTIVE",mapper.grant(ContextEvidenceService.hash(token)).getState());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_service_grants WHERE turn_id=?",Integer.class,l.turnId()));
    }
    @Test void receiptInsertFaultRollsBackQuotaAndNoSnapshotIsStored()throws Exception{
        var l=lease();var token=capabilities.issue(l.turnId());var r=request("rollback","account_query","{}");db.execute("CREATE TRIGGER reject_gateway_receipt BEFORE INSERT ON agent_tool_call_receipts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test receipt fault'");
        try{assertThrows(Exception.class,()->capabilities.call(token,r));}finally{db.execute("DROP TRIGGER reject_gateway_receipt");}
        assertEquals(0,mapper.receipts(l.turnId()).size());assertEquals(0,mapper.grant(ContextEvidenceService.hash(token)).getCallsUsed());assertFalse(capabilities.call(token,r).replay());
    }
    @Test void expiredGrantCanRenewOnlyLiveOriginalTurnAndReplayUsesOriginalTime()throws Exception{
        var l=lease();var old=capabilities.issue(l.turnId());var r=request("refreshgrant","statement","{\"month\":\"2026-09\"}");var receipt=capabilities.call(old,r);clock.set(NOW.plusSeconds(300));
        assertThrows(BankException.class,()->capabilities.call(old,r));var renewed=capabilities.renew(l.turnId(),old);var replay=capabilities.call(renewed,r);assertTrue(replay.replay());assertEquals(receipt.observedAt(),replay.observedAt());assertEquals(1,mapper.receipts(l.turnId()).size());
        assertThrows(BankException.class,()->capabilities.renew(lease().turnId(),old));
    }

    @Test void competingDifferentRequestsCannotCreateTwoProcessingTurns()throws Exception{
        String c=contexts.create("u1");
        try(var hold=ds.getConnection();var pool=Executors.newFixedThreadPool(2)){
            hold.setAutoCommit(false);try(var q=hold.prepareStatement("SELECT version FROM agent_conversations WHERE id=? FOR UPDATE")){q.setString(1,c);try(var rows=q.executeQuery()){assertTrue(rows.next());}}
            var go=new CountDownLatch(1);var entered=new CountDownLatch(2);
            java.util.function.Function<String,Callable<Boolean>> attempt=id->()->{go.await();entered.countDown();try{turns.begin("u1",new ChatRequestDTO("并发新轮",null,c,id,1L),NOW.plusSeconds(300));return true;}catch(BankException e){return false;}};
            var one=pool.submit(attempt.apply("compete1"));var two=pool.submit(attempt.apply("compete2"));go.countDown();assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->one.get(200,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->two.get(200,TimeUnit.MILLISECONDS));hold.commit();
            assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS),"Exactly one different request may start");
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_turn_receipts WHERE conversation_id=? AND state='PROCESSING'",Integer.class,c));
    }

}
