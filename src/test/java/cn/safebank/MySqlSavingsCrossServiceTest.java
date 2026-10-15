package cn.safebank;

import cn.safebank.dto.AgentResultDTO;
import cn.safebank.service.GatewayResultService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP callbacks, synthetic MySQL data and a fixed, free model. */
@EnabledIfEnvironmentVariable(named="SAFEBANK_CROSS_SERVICE_QA",matches="1")
class MySqlSavingsCrossServiceTest {
 @Test void lostJavaReceiptReplaysOriginalTaskAfterPythonRestart() throws Exception {run(true,false);}
 @Test void unconfirmedQueryStopsWithoutWorkflowOrAuthorization() throws Exception {run(false,false);}
 @Test void explicitOfflineDemoUsesRealJavaPrepareAndAnswerValidation() throws Exception {run(true,true);}
 @Test void concurrentHttpPreparesReplaySameKeyAndRejectCompetingKeys() throws Exception {
  var fixture=new MySqlAgentPreviewTest();fixture.start();
  var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
  try(var http=java.net.http.HttpClient.newHttpClient()){
   var db=MySqlAgentPreviewTest.db;var json=MySqlAgentPreviewTest.json;
   var plan=json.readTree(Files.readString(Path.of("agent-service/safebank_agent/fixtures/savings_task_demo.json"))).path("taskPlan");
   String port=MySqlAgentPreviewTest.app.getEnvironment().getProperty("local.server.port");
   for(boolean sameKey:List.of(true,false)){
    String cid=MySqlAgentPreviewTest.contexts.create("u1");MySqlAgentPreviewTest.confirmed(cid);String grant=MySqlAgentPreviewTest.token(cid);
    int before=db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class),auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
    var gate=new java.util.concurrent.CountDownLatch(1);
    var futures=new ArrayList<java.util.concurrent.Future<java.net.http.HttpResponse<String>>>();
    for(int i=0;i<2;i++){
     var body=MySqlAgentPreviewTest.previewRequest("concurrent-"+(sameKey?0:i),"savings_task_prepare",Map.of("taskPlan",plan));
     var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/internal/agent/tools")).timeout(java.time.Duration.ofSeconds(15)).header("Authorization","Bearer "+"x".repeat(64)).header("X-Agent-Grant",grant).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
     futures.add(executor.submit(()->{gate.await();return http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());}));
    }
    gate.countDown();var first=futures.get(0).get(20,TimeUnit.SECONDS);var second=futures.get(1).get(20,TimeUnit.SECONDS);
    if(sameKey){assertEquals(200,first.statusCode());assertEquals(200,second.statusCode());assertEquals(json.readTree(first.body()).path("data").path("id"),json.readTree(second.body()).path("data").path("id"));assertNotEquals(json.readTree(first.body()).path("replay"),json.readTree(second.body()).path("replay"));}
    else {assertEquals(1,(first.statusCode()==200?1:0)+(second.statusCode()==200?1:0));var rejected=first.statusCode()==200?second:first;assertEquals("PREVIEW_LIMIT",json.readTree(rejected.body()).path("code").asText());}
    assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class));assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
   }
  }finally{executor.shutdownNow();fixture.close();}
 }
 void run(boolean confirmed,boolean offlineFixture) throws Exception {
  var fixture=new MySqlAgentPreviewTest();fixture.start();
  try {
   var db=MySqlAgentPreviewTest.db;var json=MySqlAgentPreviewTest.json;
   String cid=MySqlAgentPreviewTest.contexts.create("u1");
   if(confirmed)MySqlAgentPreviewTest.confirmed(cid);
   String grant=MySqlAgentPreviewTest.token(cid);var g=MySqlAgentPreviewTest.caps.authorize(grant,true);
   int workflows=db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class),auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
   String port=MySqlAgentPreviewTest.app.getEnvironment().getProperty("local.server.port");
   String question=offlineFixture?json.readTree(Files.readString(Path.of("agent-service/safebank_agent/fixtures/savings_task_demo.json"))).path("question").asText():"撤销音乐和视频代扣，将核验节省按月存入旅游基金";
   var request=Map.of("userId","u1","conversationId",cid,"turnId",g.getTurnId(),"requestId",UUID.randomUUID().toString(),"expectedVersion",1,"text",question,"grant",grant);
   var input=Map.of("confirmed",confirmed,"offlineFixture",offlineFixture,"url","http://127.0.0.1:"+port,"request",request);
   Path log=Files.createTempFile("safebank-cross-service-",".log");
   var builder=new ProcessBuilder(System.getenv("SAFEBANK_QA_PYTHON"),"-m","tests.savings_cross_service_worker").directory(Path.of("agent-service").toFile()).redirectError(log.toFile());
   builder.environment().put("LANGSMITH_TRACING","false");builder.environment().put("LANGCHAIN_TRACING_V2","false");
   var process=builder.start();
   try {
    try(var stdin=process.getOutputStream()){stdin.write(json.writeValueAsBytes(input));}
    if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Cross-service fixture deadline");}
    assertEquals(0,process.exitValue(),"Python worker failed; inspect local temporary log");
    var report=json.readTree(process.getInputStream());
    assertEquals("RESULT_READY",report.path("state").asText(),report.path("errorCode").asText());
    assertEquals(2,report.path("modelCalls").asInt());assertEquals(1,report.path("uniqueOperationKeys").asInt());
    assertEquals(confirmed,report.path("crashed").asBoolean());
    assertEquals(confirmed?2:1,report.path("toolAttempts").asInt());
    assertEquals(workflows+(confirmed?1:0),db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class));
    assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));
    assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    if(confirmed){assertTrue(report.path("replays").get(1).asBoolean());assertTrue(UUID.fromString(report.path("workflowId").asText())!=null);}
    else assertTrue(report.path("workflowId").isNull());
    var result=json.treeToValue(report.path("result"),AgentResultDTO.class);
    var service=MySqlAgentPreviewTest.app.getBean(GatewayResultService.class);var ack=service.complete(result);service.acknowledged(g.getTurnId(),ack.resultHash(),ack.outputVersion());
    assertEquals(confirmed?1:0,((List<?>)ack.response().get("actions")).size());
   } finally {if(process.isAlive())process.destroyForcibly();}
  } finally {fixture.close();}
 }
}
