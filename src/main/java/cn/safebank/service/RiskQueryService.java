package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dao.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.UUID;
@Service public class RiskQueryService {
    private final ActionService actions;private final AuthorizationGateService gate;private final RiskUserLockService locks;private final MandateService mandates;private final ActionMapper mapper;private final Clock clock;private final ContextQueryReceiptService contextReceipts;private final org.springframework.beans.factory.ObjectProvider<AgentMandateReceiptService> agentReceipts;
    public RiskQueryService(ActionService actions,AuthorizationGateService gate,RiskUserLockService locks,MandateService mandates,ActionMapper mapper,Clock clock,ContextQueryReceiptService contextReceipts,org.springframework.beans.factory.ObjectProvider<AgentMandateReceiptService> agentReceipts){this.actions=actions;this.gate=gate;this.locks=locks;this.mandates=mandates;this.mapper=mapper;this.clock=clock;this.contextReceipts=contextReceipts;this.agentReceipts=agentReceipts;}
    public ActionVO prepareMandates(String userId,RiskChannel channel){return actions.prepare(userId,ActionKind.QUERY_MANDATES,new QueryParametersVO("OWN_BANK_MANDATES"),false);}
    public ActionVO prepareMandatesBound(String userId,RiskChannel channel,String conversationId,Long expectedVersion){
        if(conversationId==null){if(expectedVersion!=null)throw new BankException("VALIDATION_ERROR","对话版本必须绑定对话");return prepareMandates(userId,channel);}
        return locks.withUserLock(userId,()->{var receipts=agentReceipts.getIfAvailable();if(receipts==null)throw new BankException("VALIDATION_ERROR","当前模式不支持共享查询回执");receipts.validateBinding(userId,conversationId,expectedVersion);var action=prepareMandates(userId,channel);receipts.bind(userId,conversationId,expectedVersion,action.id());return action;});
    }
    public QueryExecutionReceiptVO executeAuthorized(String userId,String actionId){
        var outcome=locks.withUserLock(userId,()->{
            var a=actions.stored(userId,actionId,true);
            if(!ActionKind.QUERY_MANDATES.name().equals(a.getKind()))throw new BankException("CONFLICT","不是代扣查询动作");
            if("VERIFIED_SUCCESS".equals(a.getStatus()))return RiskOutcome.success(new QueryExecutionReceiptVO(actions.status(userId,actionId),null,null));
            var permit=gate.beginSubmission(userId,actionId,RiskChannel.HTTP);if(!permit.accepted())return RiskOutcome.<QueryExecutionReceiptVO>rejected(permit.failure());
            var data=mandates.list(userId);var now=clock.instant();
            contextReceipts.capture(userId,actionId,data,now);agentReceipts.ifAvailable(r->r.capture(userId,actionId,data,now));
            gate.consumeQuery(userId,actionId);
            if(mapper.updateState(userId,actionId,"VERIFIED_SUCCESS",UUID.randomUUID().toString(),null)!=1)throw new IllegalStateException("Query completion failed");
            return RiskOutcome.success(new QueryExecutionReceiptVO(actions.status(userId,actionId),data,now));
        });
        return RiskRejectedException.value(outcome);
    }
}
