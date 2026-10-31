package cn.safebank;
import cn.safebank.service.SavingsTaskFactProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SavingsTaskFactProjectorTest {
 @Test void completePlanScopeIsIndependentFromTheSingleCreatedStep() throws Exception {
  var input=json.readTree("""
   {"state":"AWAITING_AUTH","request":{"mandateIds":["music-id","video-id"],"sourceId":"source-secret","recipientId":"recipient-secret","firstDate":"2026-11-20","endDate":"2027-10-20","dayOfMonth":20},
    "steps":[{"key":"revoke:music-id","kind":"REVOKE","status":"AWAITING_AUTH","actionId":"action-secret","inputVersion":1}],
    "pendingAction":{"id":"action-secret","kind":"REVOKE","status":"AWAITING_AUTH","merchant":"音乐服务"}}
   """);
  var before=input.deepCopy();var result=SavingsTaskFactProjector.project(input,true);var facts=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task",result))).factCatalog();
  assertEquals("2",facts.get("savings_task.plan_scope.revocations_count").display());
  assertEquals("1",facts.get("savings_task.plan_scope.scheduled_transfers_count").display());
  assertEquals("1",facts.get("savings_task.progress.created_steps_count").display());
  assertTrue(facts.get("savings_task.plan_scope.steps_scope").display().contains("不等于"));
  assertEquals(before,input);String encoded=json.writeValueAsString(result);
  for(String secret:List.of("music-id","video-id","source-secret","recipient-secret","action-secret"))assertFalse(encoded.contains(secret));
 }
 @Test void emptyCreatedStepsDoNotEraseTheRegisteredPlan() throws Exception {
  var result=SavingsTaskFactProjector.project(json.readTree("{\"state\":\"PLANNING\",\"request\":{\"mandateIds\":[\"m\",\"v\"]},\"steps\":[]}"),false);
  var data=json.valueToTree(result);assertEquals(2,data.path("plan_scope").path("revocations_count").asInt());assertEquals(0,data.path("progress").path("created_steps_count").asInt(-1));
  assertFalse(data.path("plan_scope").has("scheduled_transfers_count"));
 }
 @Test void missingStepsAndMalformedPlanDoNotBecomeZeroOrCompleteScope() throws Exception {
  for(String request:List.of("{}","{\"mandateIds\":[]}","{\"mandateIds\":[\"m\",\"m\"]}","{\"mandateIds\":[\"m\",null]}")){
   var input=json.createObjectNode().put("state","AWAITING_AUTH");input.set("request",json.readTree(request));var result=json.valueToTree(SavingsTaskFactProjector.project(input,false));
   assertFalse(result.path("plan_scope").has("revocations_count"));assertFalse(result.path("progress").has("created_steps_count"));
  }
 }
 @Test void unknownStateKeepsScopeButDoesNotPermitContinuation() throws Exception {
  var input=json.readTree("{\"state\":\"UNKNOWN\",\"request\":{\"mandateIds\":[\"m\",\"v\"]},\"steps\":[]}");var data=json.valueToTree(SavingsTaskFactProjector.project(input,false));
  assertEquals(2,data.path("plan_scope").path("revocations_count").asInt());assertTrue(data.path("continuation").asText().contains("禁止继续"));
 }
 final ObjectMapper json=new ObjectMapper();
 String snapshot="""
 {"state":"AWAITING_AUTH","version":2,"estimatedSavingMinor":1500,"request":{"month":"2026-09","mandateIds":["music","video"],"firstDate":"2026-11-20","endDate":"2027-10-20","dayOfMonth":20},
 "steps":[{"key":"revoke:music","kind":"REVOKE","status":"VERIFIED_SUCCESS","inputVersion":1,"savingMinor":1500},{"key":"revoke:video","kind":"REVOKE","status":"CANCELLED","inputVersion":1,"savingMinor":0},{"key":"revoke:video","kind":"REVOKE","status":"AWAITING_AUTH","actionId":"action-secret","inputVersion":2,"savingMinor":0}],
 "pendingAction":{"id":"action-secret","user_id":"u1","payload_hash":"do-not-expose","kind":"REVOKE","status":"AWAITING_AUTH","merchant":"视频服务","charge_amount_minor":6800,"billing_interval":"MONTHLY"}}
 """;
 @Test void immutableSnapshotExposesCurrentObjectDatesAndVerifiedProgress() throws Exception {
  var input=json.readTree(snapshot);var original=input.deepCopy();var result=SavingsTaskFactProjector.project(input,true);
  var facts=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task",result))).factCatalog();
  assertEquals(original,input);assertEquals("待逐项确认，尚未执行",facts.get("savings_task.state_label").display());
  assertEquals("〔视频服务〕",facts.get("savings_task.pending.merchant").display());
  assertEquals("2026-11-20",facts.get("savings_task.schedule.first_date").display());
  assertEquals("68.00元",facts.get("savings_task.pending.charge_amount_minor").display());
  assertEquals("1",facts.get("savings_task.progress.verified_revocations").display());
  assertEquals("2",facts.get("savings_task.progress.created_steps_count").display());
  assertTrue(facts.get("savings_task.plan_scope.steps_scope").display().contains("按步骤标识合并"));
  assertEquals("待逐项确认，尚未执行",facts.get("savings_task.steps.1.state_label").display());
  String encoded=json.writeValueAsString(result);assertFalse(encoded.contains("action-secret"));assertFalse(encoded.contains("do-not-expose"));assertFalse(encoded.contains("user_id"));
 }
 @Test void unknownPendingOverridesStaleStepAndBlocksContinuation() throws Exception {
  var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(snapshot);input.put("state","UNKNOWN");((com.fasterxml.jackson.databind.node.ObjectNode)input.get("pendingAction")).put("status","UNKNOWN");
  var result=SavingsTaskFactProjector.project(input,false);var data=json.valueToTree(result);
  assertEquals("结果未知，需查询原动作",data.path("pending").path("state_label").asText());
  assertTrue(data.path("continuation").asText().contains("禁止继续"));assertEquals("结果未知，需查询原动作",data.path("steps").get(1).path("state_label").asText());
 }
 @Test void errorsAndMissingDataDoNotInventDatesObjectsOrSavings() throws Exception {
  assertTrue(SavingsTaskFactProjector.project(json.readTree("{\"code\":\"QUERY_CONFIRMATION_REQUIRED\"}"),true).isEmpty());
  var data=json.valueToTree(SavingsTaskFactProjector.project(json.readTree("{\"state\":\"AWAITING_AUTH\"}"),true));
  assertFalse(data.has("pending"));assertFalse(data.has("schedule"));assertFalse(data.has("verified_monthly_saving_minor"));
 }
}
