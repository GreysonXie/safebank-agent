package cn.safebank;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
/** Opt-in real browser QA, using only a fresh synthetic MySQL fixture. */
@EnabledIfEnvironmentVariable(named="SAFEBANK_BROWSER_QA",matches="1")
class MySqlSavingsBrowserTest {
 @Test void realTaskPageExecutesThreeConfirmedStepsAndKeepsHistoryCollapsed() throws Exception {run(false);}
 @Test void unknownChecksOriginalActionAndRevisionInvalidatesOldConfirmation() throws Exception {run(true);}
 void run(boolean fault) throws Exception {
  var fixture=new MySqlAgentPreviewTest();fixture.start();
  try{
   String cid=MySqlAgentPreviewTest.contexts.create("u1");MySqlAgentPreviewTest.confirmed(cid);String grant=MySqlAgentPreviewTest.token(cid);
   var json=MySqlAgentPreviewTest.json;var plan=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(Path.of("agent-service/tests/fixtures/savings_task_plan.json")));
   plan.put("analysisMonth","2026-09");((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("nodes").get(5).path("params")).put("recipientQuery","旅游基金");
   var receipt=MySqlAgentPreviewTest.caps.call(grant,MySqlAgentPreviewTest.previewRequest("browser-plan","savings_task_prepare",Map.of("taskPlan",plan)));assertFalse(receipt.data().has("code"));
   var db=MySqlAgentPreviewTest.db;int auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class),schedules=db.queryForObject("SELECT COUNT(*) FROM schedules",Integer.class);
   if(fault)MySqlAgentPreviewTest.app.getBean(cn.safebank.service.SimulationService.class).configure("u1",receipt.data().path("pendingAction").path("id").asText(),cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.AFTER_COMMIT_RESPONSE_LOST,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.LOOKUP_UNAVAILABLE);
   String port=MySqlAgentPreviewTest.app.getEnvironment().getProperty("local.server.port");String node=System.getenv().getOrDefault("SAFEBANK_QA_NODE","node");
   var process=new ProcessBuilder(node,".superpowers/savings-browser-qa.cjs","http://127.0.0.1:"+port+"/",fault?"fault":"normal").redirectErrorStream(true).redirectOutput(Path.of(".superpowers/savings-browser-console.log").toFile()).start();
   if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Browser QA deadline");}
   assertEquals(0,process.exitValue(),Files.readString(Path.of(".superpowers/savings-browser-console.log")));
   assertEquals("VERIFIED_SUCCESS",db.queryForObject("SELECT state FROM workflows WHERE id=?",String.class,receipt.data().path("id").asText()));
   assertEquals(auth+3,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));assertEquals(schedules+1,db.queryForObject("SELECT COUNT(*) FROM schedules",Integer.class));
  }finally{fixture.close();}
 }
}
