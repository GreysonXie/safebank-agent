package cn.safebank;

import cn.safebank.entity.AgentGatewayTurnEntity;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTurnStatusServiceTest {
 @Test void ownedStatusKeepsExistingFieldsWithoutLeakingInternalContent() throws Exception {
  var turns=mock(GatewayTurnService.class);var row=new AgentGatewayTurnEntity();
  row.setTurnId("original");row.setUserId("u1");row.setState("PAUSED");row.setReconcileFailures(2);row.setUserText("private question");row.setResultJson("private response");
  when(turns.read("original")).thenReturn(row);
  var status=new AgentTurnStatusService(turns).status("u1","original");
  assertEquals("original",status.turnId());assertEquals("PAUSED",status.state());assertTrue(status.requiresReview());assertEquals(0,status.outputVersion());assertEquals(2,status.reconcileFailures());
  String json=new ObjectMapper().writeValueAsString(status);assertFalse(json.contains("private"));assertFalse(json.contains("userId"));
 }
 @Test void foreignMissingAndAnonymousRequestsAreIndistinguishable() {
  var turns=mock(GatewayTurnService.class);var row=new AgentGatewayTurnEntity();row.setUserId("u2");when(turns.read("foreign")).thenReturn(row);
  var service=new AgentTurnStatusService(turns);
  for(String id:new String[]{"foreign","missing"})assertEquals("NOT_FOUND",assertThrows(BankException.class,()->service.status("u1",id)).code);
  assertEquals("NOT_FOUND",assertThrows(BankException.class,()->service.status(null,"foreign")).code);
 }
}
