package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.bank.*;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dao.ExecutionObservationMapper;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service public class ActionExecutionService {
    // Single JVM owns each demo database. Keep adapter calls/lookup in one per-action flight;
    // durable guard/grant/observation transactions remain the restart authority.
    private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock> FLIGHTS=new java.util.concurrent.ConcurrentHashMap<>();
    private final ActionService actions;private final LocalBankExecutionService local;private final BankAdapter adapter;private final ExecutionObservationMapper observations;private final AuthorizationGateService gate;private final RiskUserLockService locks;private final RiskQueryService queries;private final Clock clock;
    public ActionExecutionService(ActionService actions,LocalBankExecutionService local,BankAdapter adapter,ExecutionObservationMapper observations,AuthorizationGateService gate,RiskUserLockService locks,RiskQueryService queries,Clock clock){
        this.actions=actions;this.local=local;this.adapter=adapter;this.observations=observations;this.gate=gate;this.locks=locks;this.queries=queries;this.clock=clock;
    }
    public ActionExecutionResultVO submit(String userId,String id){
        actions.status(userId,id);
        return flight(userId,id,()->submitInFlight(userId,id));
    }
    private ActionExecutionResultVO submitInFlight(String userId,String id){
        var action=actions.status(userId,id);
        if(action.kind()==ActionKind.QUERY_MANDATES)return queries.executeAuthorized(userId,id);
        var permit=RiskRejectedException.value(gate.beginSubmission(userId,id,RiskChannel.HTTP));
        if(permit.mode()==SubmissionPermit.Mode.LOOKUP_ONLY)return observe(userId,id);
        try{return record(userId,action,adapter.submit(userId,id));}
        catch(BankException e){
            if(List.of("INSUFFICIENT_FUNDS","LIMIT_EXCEEDED","ALREADY_REVOKED","VALIDATION_ERROR").contains(e.code)&&actions.status(userId,id).status()==ActionStatus.AUTHORIZED){
                local.rejectUnaccepted(userId,id,e.code);
                return record(userId,action,new BankObservation(id,BankObservation.BankState.VERIFIED_FAILED,null,e.code,clock.instant()));
            }
            throw e;
        }
    }
    public ActionExecutionResultVO observe(String userId,String id){
        actions.status(userId,id);
        return flight(userId,id,()->observeInFlight(userId,id));
    }
    private ActionExecutionResultVO observeInFlight(String userId,String id){
        var action=actions.status(userId,id);
        if(action.kind()==ActionKind.QUERY_MANDATES)return new QueryExecutionReceiptVO(action,null,null);
        var prior=observations.selectOwned(userId,id);
        if(prior==null&&List.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED,ActionStatus.CANCELLED).contains(action.status()))return new ExecutionReceiptVO(action,null,null);
        if(prior!=null&&List.of("VERIFIED_SUCCESS","VERIFIED_FAILED").contains(prior.getState()))return new ExecutionReceiptVO(action.withObservation(ActionStatus.valueOf(prior.getState()),prior.getReceiptId(),prior.getErrorCode()),null,null);
        return record(userId,action,adapter.lookup(userId,id));
    }
    private ExecutionReceiptVO record(String userId,ActionVO action,BankObservation observed){
        if(!action.id().equals(observed.actionId()))throw new BankException("CONFLICT","银行观察与动作不匹配");
        locks.withUserLock(userId,()->{gate.completeAccepted(userId,action.id(),observed);return null;});
        var current=actions.status(userId,action.id());
        if("NOT_SUBMITTED".equals(observed.errorCode()))return new ExecutionReceiptVO(current,null,"NOT_SUBMITTED");
        return new ExecutionReceiptVO(current.withObservation(ActionStatus.valueOf(observed.state().name()),observed.receiptId(),observed.errorCode()),observed.observedAt().toString(),null);
    }
    private <T> T flight(String userId,String id,java.util.function.Supplier<T> work){
        var lock=FLIGHTS.computeIfAbsent(userId+":"+id,key->new java.util.concurrent.locks.ReentrantLock());
        lock.lock();try{return work.get();}finally{lock.unlock();}
    }
}
