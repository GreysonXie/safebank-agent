package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.agent.HttpAgentClient;
import cn.safebank.entity.AgentGatewayTurnEntity;
import cn.safebank.vo.AgentAckVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** T006：恢复入口与跨侧解锁的状态边界（不启动 Spring，不访问数据库）。 */
class AgentTurnRecoveryTest {
 final GatewayTurnService turns=mock(GatewayTurnService.class);
 final GatewayResultService results=mock(GatewayResultService.class);
 final HttpAgentClient client=mock(HttpAgentClient.class);
 final AgentTurnStatusService service=new AgentTurnStatusService(turns,results,client);
 final ObjectMapper json=new ObjectMapper();

 private AgentGatewayTurnEntity row(String state,Long outputVersion){
  var r=new AgentGatewayTurnEntity();r.setTurnId("t1");r.setUserId("u1");r.setConversationId("c1");r.setRequestId("r1");r.setState(state);r.setOutputVersion(outputVersion);return r;
 }
 private Map<String,Object> closed(){return Map.of("state","INTERRUPTED","hasOutput",false,"closed",true);}

 @Test void resolveReportsPausedWithoutOutput(){
  when(turns.original("u1","c1","r1")).thenReturn(row("PAUSED",null));
  var out=service.resolve("u1","c1","r1");
  assertEquals(true,out.get("requiresReview"));
  assertEquals(false,out.get("hasOutput"));
  assertEquals(false,out.get("closed"));
  assertFalse(out.containsKey("answer"));
 }

 @Test void resolveReturnsPersistedAnswerWhenOutputExists(){
  when(turns.original("u1","c1","r1")).thenReturn(row("ACK_PENDING",2L));
  when(results.saved("t1")).thenReturn(new AgentAckVO("t1",2L,"h",Map.of("text","原回答")));
  var out=service.resolve("u1","c1","r1");
  assertEquals(true,out.get("hasOutput"));
  assertTrue(String.valueOf(out.get("answer")).contains("原回答"));
 }

 @Test void closeUnblocksBindingWhenAgentAbandonsBlockingRun() throws Exception {
  when(turns.original("u1","c1","r1")).thenReturn(row("PAUSED",null));
  when(results.close("t1")).thenReturn(closed());
  when(client.abandonBlocking("u1","c1","t1")).thenReturn(json.readTree("{\"unblocked\":true,\"reason\":\"ABANDONED\"}"));
  var out=service.close("u1","c1","r1");
  assertEquals("INTERRUPTED",out.get("state"));
  assertEquals(true,out.get("agentUnblocked"));
 }

 @Test void closeKeepsJavaOutcomeWhenAgentSideFails() {
  when(turns.original("u1","c1","r1")).thenReturn(row("PAUSED",null));
  when(results.close("t1")).thenReturn(closed());
  when(client.abandonBlocking("u1","c1","t1")).thenThrow(new HttpAgentClient.AgentTransportException("AGENT_UNAVAILABLE"));
  var out=service.close("u1","c1","r1");
  assertEquals("INTERRUPTED",out.get("state"),"Java 侧结束事实必须保留");
  assertEquals(false,out.get("agentUnblocked"));
  assertEquals("AGENT_UNAVAILABLE",out.get("agentReason"));
 }

 @Test void closeReportsOtherBlockingRunWithoutGuessing() throws Exception {
  when(turns.original("u1","c1","r1")).thenReturn(row("PAUSED",null));
  when(results.close("t1")).thenReturn(closed());
  when(client.abandonBlocking("u1","c1","t1")).thenReturn(json.readTree("{\"unblocked\":false,\"reason\":\"OTHER_RUN\",\"blockingTurnId\":\"t9\"}"));
  var out=service.close("u1","c1","r1");
  assertEquals(false,out.get("agentUnblocked"));
  assertEquals("t9",out.get("blockingTurnId"));
 }

 @Test void closeRejectsUnknownOrOtherUsersTurn(){
  when(turns.original("u2","c1","r1")).thenThrow(GatewayTurnService.denied());
  var error=assertThrows(BankException.class,()->service.close("u2","c1","r1"));
  assertEquals("PERMISSION_DENIED",error.code);
  verifyNoInteractions(results);verifyNoInteractions(client);
 }

 @Test void closeRequiresConversationId(){
  var error=assertThrows(BankException.class,()->service.close("u1",null,"r1"));
  assertEquals("VALIDATION_ERROR",error.code);
 }
}
