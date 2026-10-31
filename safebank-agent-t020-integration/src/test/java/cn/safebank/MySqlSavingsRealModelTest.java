package cn.safebank;

import cn.safebank.dto.AgentResultDTO;
import cn.safebank.service.GatewayResultService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid opt-in; synthetic bank data, no action authorization or execution. */
@EnabledIfEnvironmentVariable(named="SAFEBANK_REAL_SAVINGS_QA", matches="1")
class MySqlSavingsRealModelTest {
 @Test void actualModelPlansCallsJavaAndAnswersThroughPythonHttp() throws Exception {
  var fixture=new MySqlAgentPreviewTest();fixture.start();
  try {
   var db=MySqlAgentPreviewTest.db;var json=MySqlAgentPreviewTest.json;
   String cid=MySqlAgentPreviewTest.contexts.create("u1");MySqlAgentPreviewTest.confirmed(cid);
   String grant=MySqlAgentPreviewTest.token(cid);var g=MySqlAgentPreviewTest.caps.authorize(grant,true);
   int before=db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class);
   int auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class);
   int ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
   String question="撤销音乐服务和视频服务的银行代扣，从日常账户把核验节省每月20日存入旅游基金，首次2026-11-20，结束2027-10-20，核对2026-09账单；只生成待确认计划，不执行。";
   var request=Map.of("userId","u1","conversationId",cid,"turnId",g.getTurnId(),"requestId",UUID.randomUUID().toString(),"expectedVersion",1,"text",question,"grant",grant,"modelRequestLimit",3);
   String port=MySqlAgentPreviewTest.app.getEnvironment().getProperty("local.server.port");
   var input=Map.of("url","http://127.0.0.1:"+port,"request",request);
   Path log=Path.of(".superpowers/2026-10-15-real-http-worker.log");
   var builder=new ProcessBuilder(System.getenv("SAFEBANK_QA_PYTHON"),"-m","tests.savings_real_http_worker").directory(Path.of("agent-service").toFile()).redirectError(log.toAbsolutePath().toFile());
   builder.environment().put("PYTHONUTF8","1");builder.environment().put("LANGSMITH_TRACING","false");builder.environment().put("LANGCHAIN_TRACING_V2","false");
   var process=builder.start();
   try {
    try(var stdin=process.getOutputStream()){stdin.write(json.writeValueAsBytes(input));}
    if(!process.waitFor(220,TimeUnit.SECONDS)){process.destroyForcibly();fail("Real HTTP test deadline; inspect ledger before any retry");}
    assertEquals(0,process.exitValue(),"Worker failed; inspect local log and budget ledger, do not resend automatically");
    var report=json.readTree(process.getInputStream());
    Files.writeString(Path.of(System.getenv("SAFEBANK_REAL_REPORT")),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    assertEquals("RESULT_READY",report.path("state").asText(),report.path("errorCode").asText());
    assertTrue(report.path("modelSends").asInt()>=2 && report.path("modelSends").asInt()<=3);assertEquals(1,report.path("toolCalls").asInt());
    assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class));
    assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));
    assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    var result=json.treeToValue(report.path("result"),AgentResultDTO.class);
    var service=MySqlAgentPreviewTest.app.getBean(GatewayResultService.class);var ack=service.complete(result);
    service.acknowledged(g.getTurnId(),ack.resultHash(),ack.outputVersion());
    assertEquals(1,((List<?>)ack.response().get("actions")).size());
    var plan=json.readTree(db.queryForObject("SELECT request_json FROM workflows",String.class));
    assertEquals("2026-09",plan.path("month").asText());
    var finalReport=(com.fasterxml.jackson.databind.node.ObjectNode)report;
    finalReport.remove("result");finalReport.put("javaGatewayAccepted",true);finalReport.put("workflowDelta",1);finalReport.put("authorizationDelta",0);finalReport.put("ledgerDelta",0);
    finalReport.set("response",json.valueToTree(ack.response()));
    Files.writeString(Path.of(System.getenv("SAFEBANK_REAL_REPORT")),json.writerWithDefaultPrettyPrinter().writeValueAsString(finalReport));
   }finally{if(process.isAlive())process.destroyForcibly();}
  }finally{fixture.close();}
 }
}
