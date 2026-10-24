package cn.safebank;

import cn.safebank.common.ContextContracts.*;
import cn.safebank.dao.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.model.DebugRequestBudget;
import cn.safebank.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayBudgetPreflightTest {
 @TempDir Path temp;
 @Test void exhaustedQuotaRollsBackNewPaidTurnWhileReplayAndMockRemainAvailable(){
  var contexts=mock(ContextService.class);var mapper=mock(AgentGatewayMapper.class);
  var manager=mock(PlatformTransactionManager.class);var status=new SimpleTransactionStatus();
  when(manager.getTransaction(any())).thenReturn(status);
  var clock=Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"),ZoneOffset.UTC);
  var service=new GatewayTurnService(contexts,mapper,mock(AgentConversationMapper.class),clock,manager);
  var budget=new DebugRequestBudget(temp.resolve("usage.json"),1);budget.reserve();
  var env=new MockEnvironment().withProperty("bank.agent.model-mode","deepseek");
  ReflectionTestUtils.setField(service,"budget",budget);ReflectionTestUtils.setField(service,"environment",env);
  ReflectionTestUtils.setField(service,"limits",mock(ObjectProvider.class));
  var request=new ChatRequestDTO("账单",null,"cid","rid",1L);
  when(contexts.begin("u1",request)).thenReturn(new TurnStart(new TurnLease("u1","cid","tid",1,"rid"),null,false));
  var expiry=clock.instant().plusSeconds(600);
  assertEquals("MODEL_REQUEST_BUDGET_EXCEEDED",assertThrows(BankException.class,()->service.begin("u1",request,expiry)).code);
  verify(manager).rollback(status);verifyNoInteractions(mapper);
  var replay=new TurnStart(null,Map.of("text","原回答"),false);when(contexts.begin("u1",request)).thenReturn(replay);
  assertSame(replay,service.begin("u1",request,expiry));
  var paused=new TurnStart(null,null,true);when(contexts.begin("u1",request)).thenReturn(paused);
  assertSame(paused,service.begin("u1",request,expiry));
  env.setProperty("bank.agent.model-mode","mock");
  var mockTurn=new TurnStart(new TurnLease("u1","cid","tid",1,"rid"),null,false);when(contexts.begin("u1",request)).thenReturn(mockTurn);
  when(mapper.insertGateway(any())).thenReturn(1);
  assertSame(mockTurn,service.begin("u1",request,expiry));verify(mapper).insertGateway(any());
  assertEquals(1,budget.snapshot().reserved());
 }
}
