package cn.safebank.service;
import cn.safebank.common.*;
import cn.safebank.dao.*;
import cn.safebank.entity.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service public class RiskReconciliationService {
    private final RiskUserLockService locks;private final ActionMapper actions;private final ExecutionObservationMapper observations;private final RiskTransferUsageMapper usage;private final RiskUserGuardMapper guards;private final TransactionMapper transactions;private final ActionPayloadCodec codec;private final Clock clock;
    public RiskReconciliationService(RiskUserLockService locks,ActionMapper actions,ExecutionObservationMapper observations,RiskTransferUsageMapper usage,RiskUserGuardMapper guards,TransactionMapper transactions,ActionPayloadCodec codec,Clock clock){this.locks=locks;this.actions=actions;this.observations=observations;this.usage=usage;this.guards=guards;this.transactions=transactions;this.codec=codec;this.clock=clock;}
    public ReconciliationResult reconcileUser(String user){
        return locks.withUserLock(user,()->{
            var unresolved=new ArrayList<String>();int migrated=0;
            for(var a:actions.selectByUser(user)){
                if(!ActionKind.TRANSFER.name().equals(a.getKind()))continue;
                var obs=observations.selectOwned(user,a.getId());var existing=usage.selectOwned(user,a.getId());
                if(existing!=null){if(existing.getBusinessDate()==null)unresolved.add(a.getId());continue;}
                if(obs==null&&!List.of("VERIFIED_SUCCESS","PENDING","UNKNOWN").contains(a.getStatus()))continue;
                actions.selectOwnedForUpdate(user,a.getId());long amount;
                try{amount=((cn.safebank.vo.TransferParametersVO)codec.decode(ActionKind.TRANSFER,a.getPayload())).amountMinor();if(amount<=0)throw new IllegalArgumentException();}catch(Exception invalid){unresolved.add(a.getId());continue;}
                var debit=transactions.selectActionDebit(user,a.getId()+":debit");LocalDate date=debit==null?null:debit.getBookedOn();
                if(debit!=null&&debit.getAmountMinor()!=-amount){unresolved.add(a.getId());continue;}
                boolean success="VERIFIED_SUCCESS".equals(a.getStatus())&&debit!=null;
                var row=new RiskTransferUsageEntity();row.setActionId(a.getId());row.setUserId(user);row.setProvenance("LEGACY_OBSERVED");row.setAmountMinor(amount);row.setBusinessDate(date);row.setState(success?"COMMITTED":"SUBMITTED");row.setReservedUntil(OffsetDateTime.now(clock));row.setUpdatedAt(OffsetDateTime.now(clock));if(usage.insert(row)!=1)throw new IllegalStateException("Legacy usage insert failed");migrated++;
                if(date==null)unresolved.add(a.getId());
            }
            var g=guards.selectForUpdate(user);g.setReconciliationRequired(!unresolved.isEmpty());g.setUpdatedAt(OffsetDateTime.now(clock));g.setRevision(g.getRevision()+1);if(guards.update(g)!=1)throw new IllegalStateException("Reconciliation guard update failed");
            return new ReconciliationResult(migrated,unresolved.size(),!unresolved.isEmpty(),List.copyOf(unresolved));
        });
    }
}
