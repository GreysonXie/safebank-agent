package cn.safebank;

import cn.safebank.entity.AgentGatewayTurnEntity;
import cn.safebank.entity.AgentToolCallReceiptEntity;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTurnStatusServiceTest {
 private static AgentTurnStatusService service(cn.safebank.dao.AgentGatewayMapper receipts) {
  return new AgentTurnStatusService(mock(GatewayTurnService.class),mock(GatewayResultService.class),
      mock(cn.safebank.agent.HttpAgentClient.class),receipts,new ObjectMapper());
 }
 @Test void ownedStatusKeepsExistingFieldsWithoutLeakingInternalContent() throws Exception {
  var turns=mock(GatewayTurnService.class);var row=new AgentGatewayTurnEntity();
  row.setTurnId("original");row.setUserId("u1");row.setState("PAUSED");row.setReconcileFailures(2);row.setUserText("private question");row.setResultJson("private response");
  when(turns.read("original")).thenReturn(row);
  var status=new AgentTurnStatusService(turns,mock(GatewayResultService.class),mock(cn.safebank.agent.HttpAgentClient.class),
      mock(cn.safebank.dao.AgentGatewayMapper.class),new ObjectMapper()).status("u1","original");
  assertEquals("original",status.turnId());assertEquals("PAUSED",status.state());assertTrue(status.requiresReview());assertEquals(0,status.outputVersion());assertEquals(2,status.reconcileFailures());
  String json=new ObjectMapper().writeValueAsString(status);assertFalse(json.contains("private"));assertFalse(json.contains("userId"));
 }
 @Test void foreignMissingAndAnonymousRequestsAreIndistinguishable() {
  var turns=mock(GatewayTurnService.class);var row=new AgentGatewayTurnEntity();row.setUserId("u2");when(turns.read("foreign")).thenReturn(row);
  var service=new AgentTurnStatusService(turns,mock(GatewayResultService.class),mock(cn.safebank.agent.HttpAgentClient.class),
      mock(cn.safebank.dao.AgentGatewayMapper.class),new ObjectMapper());
  for(String id:new String[]{"foreign","missing"})assertEquals("NOT_FOUND",assertThrows(BankException.class,()->service.status("u1",id)).code);
  assertEquals("NOT_FOUND",assertThrows(BankException.class,()->service.status(null,"foreign")).code);
 }
 @Test void statementQueryReadsTheLatestRangeReceiptOfTheOwnedConversation() throws Exception {
  var receipts=mock(cn.safebank.dao.AgentGatewayMapper.class);
  var row=new AgentToolCallReceiptEntity();
  row.setResultJson("{\"query\":{\"start\":\"2026-08-01\",\"end\":\"2026-10-29\",\"bucket\":\"month\"},\"totals\":{\"expense_minor\":12345}}");
  row.setObservedAt(OffsetDateTime.parse("2026-10-30T02:00:00Z"));
  when(receipts.latestReceiptByTool("u1","c1","statement_query")).thenReturn(row);
  var found=service(receipts).statementQuery("u1","c1");
  assertEquals(true,found.get("found"));
  assertEquals("2026-08-01",((com.fasterxml.jackson.databind.JsonNode)found.get("query")).path("start").asText());
  assertEquals("2026-10-30T02:00:00Z",found.get("observedAt"));
  assertNotNull(found.get("result"));
  assertEquals(false,service(receipts).statementQuery("u1","c2").get("found"));
  assertEquals("VALIDATION_ERROR",assertThrows(BankException.class,()->service(receipts).statementQuery("u1",null)).code);
  assertEquals("VALIDATION_ERROR",assertThrows(BankException.class,()->service(receipts).statementQuery("u1","bad id!")).code);
 }
 @Test void statementQueryReportsBrokenReceiptsInsteadOfShowingStaleNumbers() throws Exception {
  var receipts=mock(cn.safebank.dao.AgentGatewayMapper.class);
  var row=new AgentToolCallReceiptEntity();
  row.setResultJson("{\"totals\":{\"expense_minor\":1}}");
  when(receipts.latestReceiptByTool("u1","c1","statement_query")).thenReturn(row);
  assertEquals(false,service(receipts).statementQuery("u1","c1").get("found"));
  row.setResultJson("not json");
  var broken=service(receipts).statementQuery("u1","c1");
  assertEquals(false,broken.get("found"));assertEquals(true,broken.get("broken"));
 }
}
