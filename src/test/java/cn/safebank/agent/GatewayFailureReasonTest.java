package cn.safebank.agent;

import cn.safebank.BankException;
import cn.safebank.dao.AgentGatewayMapper;
import cn.safebank.entity.AgentGatewayTurnEntity;
import cn.safebank.entity.AuditEventEntity;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** B4 失败原因不清：暂停/中断要留下真实原因码，用户看到的消息也要能定位原因。 */
class GatewayFailureReasonTest {
    private final List<AuditEventEntity> auditEvents=new ArrayList<>();
    private AgentGatewayTurnEntity row;

    private GatewayResultService service(){return service("PROCESSING",null);}

    private GatewayResultService service(String state,Long outputVersion){
        var turns=mock(GatewayTurnService.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        row=new AgentGatewayTurnEntity();
        row.setTurnId("turn-1");row.setUserId("u1");row.setConversationId("conversation-1");row.setState(state);
        row.setInputVersion(1L);row.setOutputVersion(outputVersion);row.setReconcileFailures(2);
        when(turns.lock("turn-1",false)).thenReturn(row);
        when(turns.lock("turn-1",true)).thenReturn(row);
        when(turns.now()).thenReturn(java.time.OffsetDateTime.parse("2026-10-31T06:00:00Z"));
        var mapper=mock(AgentGatewayMapper.class);
        when(mapper.updateGateway(any())).thenReturn(1);
        var audit=mock(AuditService.class);
        doAnswer(call->{var event=new AuditEventEntity();event.setUserId(call.getArgument(0));event.setActionId(call.getArgument(1));event.setEvent(call.getArgument(2));auditEvents.add(event);return null;})
            .when(audit).record(anyString(),anyString(),anyString());
        var service=new GatewayResultService(turns,mock(AgentCapabilityService.class),mapper,mock(ContextService.class),new ObjectMapper(),manager);
        ReflectionTestUtils.setField(service,"audit",audit);
        return service;
    }

    @Test void closingAPausedTurnWithoutResultIsAllowedAndRecorded(){
        var service=service("PAUSED",null);
        var outcome=service.close("turn-1");
        assertEquals("INTERRUPTED",outcome.get("state"));
        assertEquals(true,outcome.get("closed"));
        assertEquals("INTERRUPTED",row.getState(),"本侧置为终态");
        assertEquals(1,auditEvents.size());
        assertTrue(auditEvents.getFirst().getEvent().startsWith("AGENT_CLOSED_BY_USER · "));
    }

    @Test void closingIsRefusedWhenTheTurnStillHasAReviewableResult(){
        var service=service("PAUSED",2L);
        var failure=assertThrows(BankException.class,()->service.close("turn-1"));
        assertEquals("CONFLICT",failure.code);
        assertEquals("PAUSED",row.getState(),"有结果的轮次不得被结束");
        assertTrue(failure.getMessage().contains("已有可核验结果"));
    }

    @Test void closingIsRefusedForCompletedOrRunningTurns(){
        assertEquals("CONFLICT",assertThrows(BankException.class,()->service("ACKED",2L).close("turn-1")).code);
        assertEquals("CONFLICT",assertThrows(BankException.class,()->service("PROCESSING",null).close("turn-1")).code);
        assertEquals("CONFLICT",assertThrows(BankException.class,()->service("RESULT_READY",null).close("turn-1")).code);
    }

    @Test void closingAnAlreadyClosedTurnIsIdempotent(){
        var service=service("INTERRUPTED",null);
        var outcome=service.close("turn-1");
        assertEquals("INTERRUPTED",outcome.get("state"));
        assertEquals(true,outcome.get("closed"));
        assertTrue(auditEvents.isEmpty(),"重复结束不再写新审计");
    }

    @Test void pausingWritesTheRealReasonToTheAuditTrail(){
        var service=service();
        service.pause("turn-1","AGENT_RESULT_MODEL_TURN_BUDGET_EXCEEDED");
        assertEquals(1,auditEvents.size(),"暂停必须留下一条原因记录");
        assertEquals("u1",auditEvents.getFirst().getUserId());
        assertEquals("turn-1",auditEvents.getFirst().getActionId());
        assertTrue(auditEvents.getFirst().getEvent().startsWith("AGENT_PAUSED · "));
        assertTrue(auditEvents.getFirst().getEvent().contains("MODEL_TURN_BUDGET_EXCEEDED"),"审计事件带真实原因码");
    }

    @Test void transportFailureThresholdPausesWithTheTransportReason(){
        var service=service();
        service.deferred("turn-1",true,"AGENT_TRANSPORT_UNAVAILABLE:start");
        assertEquals(1,auditEvents.size(),"第三次失败才暂停，并当场记录原因");
        assertTrue(auditEvents.getFirst().getEvent().startsWith("AGENT_RECONCILE_PAUSED · "));
        assertTrue(auditEvents.getFirst().getEvent().contains("AGENT_TRANSPORT_UNAVAILABLE"));
    }

    @Test void missingRunIsRecordedAsInterruptedWithReason(){
        var service=service();
        service.noRun("turn-1","AGENT_RUN_MISSING");
        assertEquals(1,auditEvents.size());
        assertTrue(auditEvents.getFirst().getEvent().startsWith("AGENT_INTERRUPTED · "));
        assertTrue(auditEvents.getFirst().getEvent().contains("AGENT_RUN_MISSING"));
    }

    @Test void unknownReasonIsRecordedAsUnspecified(){
        var service=service();
        service.pause("turn-1","");
        assertEquals(1,auditEvents.size());
        assertTrue(auditEvents.getFirst().getEvent().contains("UNSPECIFIED"));
    }

    @Test void auditFailureDoesNotBreakTheTurnProcessing(){
        var service=service();
        var audit=(AuditService)ReflectionTestUtils.getField(service,"audit");
        doThrow(new IllegalStateException("audit down")).when(audit).record(anyString(),anyString(),anyString());
        assertDoesNotThrow(()->service.pause("turn-1","AGENT_RESULT_UNKNOWN"),"审计不可用不能反过来打断轮次处理");
    }

    @Test void reviewMessagesNameTheCauseInsteadOfAGenericNotice(){
        assertTrue(GatewayCoordinator.reviewMessage("ANSWER_REPAIR_EXHAUSTED").contains("没有执行任何资金操作"));
        assertTrue(GatewayCoordinator.reviewMessage("BANK_CALLBACK_REJECTED").contains("银行侧明确拒绝"));
        assertTrue(GatewayCoordinator.reviewMessage("AGENT_BUSY").contains("并发已满"));
        assertTrue(GatewayCoordinator.reviewMessage("MODEL_TURN_BUDGET_EXCEEDED").contains("模型请求次数已达上限"));
        assertTrue(GatewayCoordinator.reviewMessage("SOME_NEW_CODE").contains("SOME_NEW_CODE"),"未知错误码要原样回传，便于定位");
        assertEquals("原轮次状态需要核对，已暂停自动处理",GatewayCoordinator.reviewMessage(""));
        for(String code:List.of("ANSWER_REPAIR_EXHAUSTED","BANK_CALLBACK_REJECTED","AGENT_BUSY","MODEL_TURN_BUDGET_EXCEEDED")){
            String message=GatewayCoordinator.reviewMessage(code);
            assertFalse(message.contains("已完成"),"失败说明不得声称完成："+code);
            assertFalse(message.contains("转账成功"),"失败说明不得声称资金动作成功："+code);
        }
    }

    @Test void reviewFailureStillCarriesTheBusinessCode(){
        var failure=assertThrows(BankException.class,()->{throw new BankException("AGENT_REVIEW_REQUIRED",GatewayCoordinator.reviewMessage("MODEL_EFFECT_UNKNOWN"));});
        assertEquals("AGENT_REVIEW_REQUIRED",failure.code);
    }
}
