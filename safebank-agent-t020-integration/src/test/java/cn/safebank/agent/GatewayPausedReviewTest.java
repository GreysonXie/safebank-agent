package cn.safebank.agent;

import cn.safebank.BankException;
import cn.safebank.common.ContextContracts.TurnStart;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.entity.AgentGatewayTurnEntity;
import cn.safebank.service.*;
import cn.safebank.vo.AgentAckVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayPausedReviewTest {
 final GatewayTurnService turns=mock(GatewayTurnService.class);
 final AgentCapabilityService capabilities=mock(AgentCapabilityService.class);
 final GatewayResultService results=mock(GatewayResultService.class);
 final HttpAgentClient client=mock(HttpAgentClient.class);
 final GatewayCoordinator coordinator=new GatewayCoordinator(turns,capabilities,results,client,mock(ContextService.class));
 final AgentGatewayTurnEntity row=new AgentGatewayTurnEntity();
 final ChatRequestDTO request=new ChatRequestDTO("原问题",null,"cid","rid",1L);
 final MockHttpSession session=new MockHttpSession();
 final ObjectMapper json=new ObjectMapper();
 GatewayPausedReviewTest(){
  row.setTurnId("tid");row.setState("PAUSED");
  when(turns.original("u1","cid","rid")).thenReturn(row);
  when(turns.read("tid")).thenReturn(row);
 }
 @Test void pausedRecoveryHintDoesNotIssueGrantOrRecoverRun(){
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,null,true));
  when(client.get("tid")).thenReturn(json.createObjectNode().put("state","RECOVERY_REQUIRED"));
  var error=assertThrows(BankException.class,()->coordinator.chat("u1",request,session));
  assertEquals("AGENT_REVIEW_REQUIRED",error.code);
  verifyNoInteractions(capabilities);verify(client,never()).recover(anyString(),anyString());
  verify(results).pause(eq("tid"),anyString());
 }
 @Test void savedReplyRepairsMatchingRemoteAckWithoutStartingAnotherRun(){
  var response=Map.<String,Object>of("text","原回答");
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,response,false));
  row.setOutputVersion(2L);row.setResultHash("hash");
  when(results.saved("tid")).thenReturn(new AgentAckVO("tid",2L,"hash",response));
  when(client.get("tid")).thenReturn(json.createObjectNode().put("state","COMPLETED").put("outputVersion",2).put("resultHash","hash"));
  assertEquals(response,coordinator.chat("u1",request,session));
  verify(results).acknowledged("tid","hash",2L);
  verifyNoInteractions(capabilities);verify(client,never()).start(any(),anyString());
 }
 @Test void mismatchedAckKeepsPauseAndOriginalSavedReply(){
  var response=Map.<String,Object>of("text","原回答");
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,response,false));
  row.setOutputVersion(2L);row.setResultHash("hash");
  when(results.saved("tid")).thenReturn(new AgentAckVO("tid",2L,"hash",response));
  when(client.get("tid")).thenReturn(json.createObjectNode().put("state","COMPLETED").put("outputVersion",2).put("resultHash","other"));
  assertEquals(response,coordinator.chat("u1",request,session));
  verify(results).pause(eq("tid"),anyString());verify(results,never()).acknowledged(anyString(),anyString(),anyLong());
 }
 @Test void alreadyAckedReplayDoesNotContactRemote(){
  var response=Map.<String,Object>of("text","原回答");row.setState("ACKED");
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,response,false));
  assertEquals(response,coordinator.chat("u1",request,session));
  verifyNoInteractions(client,capabilities,results);
 }
 @Test void readySavedResultUsesOnlyOriginalAck(){
  var response=Map.<String,Object>of("text","原回答");
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,response,false));
  var ack=new AgentAckVO("tid",2L,"hash",response);when(results.saved("tid")).thenReturn(ack);
  var reply=json.createObjectNode().put("state","RESULT_READY");when(client.get("tid")).thenReturn(reply);
  var remote=new cn.safebank.dto.AgentResultDTO("tid",1L,json.createObjectNode(),java.util.List.of());
  when(client.result(reply,"tid")).thenReturn(remote);when(results.resultHash(remote)).thenReturn("hash");
  assertEquals(response,coordinator.chat("u1",request,session));
  verify(client).ack(ack);verify(results).acknowledged("tid","hash",2L);verifyNoInteractions(capabilities);
  verify(client,never()).start(any(),anyString());verify(client,never()).recover(anyString(),anyString());
 }
 @Test void missingRunDoesNotDiscardCommittedReplyOrUnlockConversation(){
  var response=Map.<String,Object>of("text","原回答");
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,response,false));
  when(results.saved("tid")).thenReturn(new AgentAckVO("tid",2L,"hash",response));
  when(client.get("tid")).thenReturn(json.createObjectNode().put("state","NO_RUN"));
  assertEquals(response,coordinator.chat("u1",request,session));
  verify(results).pause(eq("tid"),anyString());verify(results,never()).noRun(anyString());verifyNoInteractions(capabilities);
 }
 @Test void unavailableRemoteKeepsOriginalReplyAndPauses(){
  var response=Map.<String,Object>of("text","原回答");
  when(turns.begin(eq("u1"),any(),any())).thenReturn(new TurnStart(null,response,false));
  when(results.saved("tid")).thenReturn(new AgentAckVO("tid",2L,"hash",response));
  when(client.get("tid")).thenThrow(new HttpAgentClient.AgentTransportException("AGENT_UNAVAILABLE"));
  assertEquals(response,coordinator.chat("u1",request,session));verify(results).pause(eq("tid"),anyString());
  verify(client,never()).ack(any());verifyNoInteractions(capabilities);
 }
}
