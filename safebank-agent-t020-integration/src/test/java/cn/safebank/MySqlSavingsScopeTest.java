package cn.safebank;
import cn.safebank.dto.AgentResultDTO;
import cn.safebank.service.GatewayResultService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owned Java tool receipts; synthetic database, no funds authorization. */
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlSavingsScopeTest {
 @Test void planScopeSurvivesReceiptReplayAndOriginalTaskBecomingUnknown() throws Exception {
  var fixture=new MySqlAgentPreviewTest();fixture.start();
  try {
   var db=MySqlAgentPreviewTest.db;var json=MySqlAgentPreviewTest.json;var caps=MySqlAgentPreviewTest.caps;
   String cid=MySqlAgentPreviewTest.contexts.create("u1");MySqlAgentPreviewTest.confirmed(cid);
   int auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
   var balances=db.queryForList("SELECT id,balance_minor FROM accounts ORDER BY id");
   String grant=MySqlAgentPreviewTest.token(cid);
   var plan=(ObjectNode)json.readTree(Files.readString(Path.of("agent-service/tests/fixtures/savings_task_plan.json")));
   plan.put("analysisMonth","2026-09");((ObjectNode)plan.path("nodes").get(5).path("params")).put("recipientQuery","旅游基金");
   var request=MySqlAgentPreviewTest.previewRequest("plan-scope-original","savings_task_prepare",Map.of("taskPlan",plan));
   var first=caps.call(grant,request);var replay=caps.call(grant,request);
   assertTrue(replay.replay());assertEquals(first.resultHash(),replay.resultHash());
   assertEquals("2",first.facts().get("savings_task.plan_scope.revocations_count").display());
   assertEquals("1",first.facts().get("savings_task.plan_scope.scheduled_transfers_count").display());
   assertEquals("1",first.facts().get("savings_task.progress.created_steps_count").display());
   String workflow=first.data().path("id").asText(),action=first.data().path("pendingAction").path("id").asText();
   var turn=caps.authorize(grant,true);var results=MySqlAgentPreviewTest.app.getBean(GatewayResultService.class);
   var ack=results.complete(new AgentResultDTO(turn.getTurnId(),1,json.createObjectNode().put("kind","analysis").put("answer","登记计划包含{{savings_task.plan_scope.revocations_count}}项撤销和{{savings_task.plan_scope.scheduled_transfers_count}}项定时转存；按步骤标识合并后的当前步骤数为{{savings_task.progress.created_steps_count}}。{{savings_task.plan_scope.steps_scope}}。尚未执行银行操作。"),List.of(first.evidenceId())));
   results.acknowledged(turn.getTurnId(),ack.resultHash(),ack.outputVersion());
   String statusGrant=MySqlAgentPreviewTest.nextToken(cid,"核对原任务计划范围");
   var status=caps.call(statusGrant,MySqlAgentPreviewTest.previewRequest("plan-scope-status","savings_task_status",Map.of("workflowId",workflow)));
   assertEquals("2",status.facts().get("savings_task_status.plan_scope.revocations_count").display());
   int actions=db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class),workflows=db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class);
   db.update("UPDATE actions SET status='UNKNOWN' WHERE id=?",action);db.update("UPDATE workflows SET state='UNKNOWN' WHERE id=?",workflow);
   var unknown=caps.call(statusGrant,MySqlAgentPreviewTest.previewRequest("plan-scope-unknown","savings_task_status",Map.of("workflowId",workflow)));
   assertEquals("2",unknown.facts().get("savings_task_status.plan_scope.revocations_count").display());
   assertTrue(unknown.facts().get("savings_task_status.continuation").display().contains("禁止继续"));
   assertEquals(actions,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));assertEquals(workflows,db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class));
   assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
   assertEquals(balances,db.queryForList("SELECT id,balance_minor FROM accounts ORDER BY id"));
  } finally {fixture.close();}
 }
}
