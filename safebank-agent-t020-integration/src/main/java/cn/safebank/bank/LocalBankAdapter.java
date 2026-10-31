package cn.safebank.bank;
import cn.safebank.service.ActionService;
import cn.safebank.service.LocalBankExecutionService;
import cn.safebank.common.ActionStatus;
import cn.safebank.vo.ActionVO;
import org.springframework.stereotype.Component;
import java.time.Clock;
import static cn.safebank.bank.BankObservation.BankState.*;
@Component
public class LocalBankAdapter implements BankAdapter {
    private final LocalBankExecutionService execution;private final ActionService actions;private final Clock clock;
    public LocalBankAdapter(LocalBankExecutionService execution,ActionService actions,Clock clock){this.execution=execution;this.actions=actions;this.clock=clock;}
    public BankObservation submit(String user,String id){return observation(id,actions.status(user,id).status()==ActionStatus.PENDING?execution.completePending(user,id):execution.submit(user,id));}
    public BankObservation lookup(String user,String id){var action=actions.status(user,id);if(java.util.List.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED,ActionStatus.CANCELLED).contains(action.status()))return new BankObservation(id,UNKNOWN,null,"NOT_SUBMITTED",clock.instant());return observation(id,action);}
    public BankObservation observation(String id,ActionVO action){var state=switch(action.status()){case VERIFIED_SUCCESS->VERIFIED_SUCCESS;case VERIFIED_FAILED->VERIFIED_FAILED;case PENDING->PENDING;default->UNKNOWN;};return new BankObservation(id,state,action.receiptId(),action.errorCode(),clock.instant());}
}
