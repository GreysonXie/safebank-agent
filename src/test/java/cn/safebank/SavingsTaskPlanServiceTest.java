package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class SavingsTaskPlanServiceTest {
 final ObjectMapper json=new ObjectMapper();
 final AgentMandateReceiptService receipts=mock(AgentMandateReceiptService.class);
 final AccountService accounts=mock(AccountService.class);
 final RecipientService recipients=mock(RecipientService.class);
 final WorkflowService workflows=mock(WorkflowService.class);
 final cn.safebank.dao.AgentGatewayMapper gateway=mock(cn.safebank.dao.AgentGatewayMapper.class);
 final cn.safebank.dao.AgentTurnReceiptMapper turns=mock(cn.safebank.dao.AgentTurnReceiptMapper.class);
 SavingsTaskPlanDTO plan() throws Exception {return new SavingsTaskPlanValidator(json).validate(json.readTree(Files.readString(Path.of("agent-service/tests/fixtures/savings_task_plan.json"))));}
 SavingsTaskPlanService service(){return new SavingsTaskPlanService(receipts,accounts,recipients,workflows,json,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC),gateway,turns);}
 void valid() throws Exception {
  when(receipts.read("u1","c1")).thenReturn(json.readTree("{\"mandates\":[{\"id\":\"music\",\"merchant\":\"音乐服务\",\"status\":\"ACTIVE\",\"billing_interval\":\"MONTHLY\"},{\"id\":\"video\",\"merchant\":\"视频服务\",\"status\":\"ACTIVE\",\"billing_interval\":\"MONTHLY\"}]}"));
  when(accounts.list("u1")).thenReturn(List.of(new AccountVO("checking","日常账户",100000)));
  when(recipients.search("u1","储蓄账户")).thenReturn(List.of(new RecipientVO("travel-recipient","本人","储蓄","储蓄账户")));
 }
 @Test void missingConfirmedQueryDoesNotCreateTask() throws Exception {
  when(receipts.read("u1","c1")).thenReturn(Map.of("code","QUERY_CONFIRMATION_REQUIRED"));
  assertEquals("QUERY_CONFIRMATION_REQUIRED",json.valueToTree(service().prepare("u1","c1","turn","key",plan())).path("code").asText());verifyNoInteractions(workflows);
 }
 @Test void compilesOwnedObjectsAndStableIdentityWithoutExecuting() throws Exception {
  valid();service().prepare("u1","c1","turn","key",plan());service().prepare("u1","c1","turn","key",plan());
  var ids=org.mockito.ArgumentCaptor.forClass(UUID.class);var requests=org.mockito.ArgumentCaptor.forClass(WorkflowRequestDTO.class);
  verify(workflows,times(2)).createIdentified(eq("u1"),ids.capture(),requests.capture());
  assertEquals(ids.getAllValues().get(0),ids.getAllValues().get(1));var r=requests.getValue();
  assertEquals(List.of("music","video"),r.mandateIds());assertEquals("2026-10",r.month());assertEquals("checking",r.sourceId());assertTrue(r.onlyConfirmedSavings());
 }
 @Test void nonMonthlySelectionDoesNotCreateTask() throws Exception {
  valid();var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(receipts.read("u1","c1"));((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.path("mandates").get(1)).put("billing_interval","QUARTERLY");when(receipts.read("u1","c1")).thenReturn(snapshot);
  assertEquals("MANDATE_NOT_MONTHLY",json.valueToTree(service().prepare("u1","c1","turn","key",plan())).path("code").asText());verifyNoInteractions(workflows);
 }
 @Test void ambiguousAccountDoesNotCreateTask() throws Exception {
  valid();when(accounts.list("u1")).thenReturn(List.of(new AccountVO("one","日常账户",1000),new AccountVO("two","日常账户",1000)));
  assertEquals("SOURCE_CLARIFICATION_REQUIRED",json.valueToTree(service().prepare("u1","c1","turn","key",plan())).path("code").asText());verifyNoInteractions(workflows);
 }
 @Test void expiredReceiptStopsBeforeObjectResolution() throws Exception {
  valid();doThrow(new BankException("QUERY_CONFIRMATION_REQUIRED","过期")).when(receipts).requireValidSnapshot(eq("u1"),eq("c1"),any());
  assertEquals("QUERY_CONFIRMATION_REQUIRED",assertThrows(BankException.class,()->service().prepare("u1","c1","turn","key",plan())).code);
  verifyNoInteractions(accounts,recipients,workflows);
 }
 @Test void existingUnknownTaskCannotBeRegisteredAgain() throws Exception {
  var turn=new cn.safebank.entity.AgentTurnReceiptEntity();turn.setId("old-turn");when(turns.selectByConversation("u1","c1")).thenReturn(List.of(turn));
  var row=new cn.safebank.entity.AgentToolCallReceiptEntity();row.setTurnId("old-turn");row.setToolName("savings_task_prepare");UUID id=UUID.randomUUID();row.setResultJson("{\"id\":\""+id+"\"}");row.setResultHash(ContextEvidenceService.hash(row.getResultJson()));when(gateway.receipts("old-turn")).thenReturn(List.of(row));
  var existing=mock(WorkflowVO.class);when(existing.state()).thenReturn("UNKNOWN");when(workflows.get("u1",id)).thenReturn(existing);
  assertEquals("TASK_ALREADY_EXISTS",json.valueToTree(service().prepare("u1","c1","new-turn","key",plan())).path("code").asText());
  verify(workflows,never()).createIdentified(anyString(),any(),any());verifyNoInteractions(receipts,accounts,recipients);
 }
}
