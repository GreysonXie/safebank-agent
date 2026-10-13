package cn.safebank.bank;
import cn.safebank.service.ActionService;
import cn.safebank.service.LocalBankExecutionService;
import cn.safebank.service.SimulationService;
import cn.safebank.common.ActionStatus;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import java.time.Clock;
import static cn.safebank.bank.BankObservation.BankState.*;
@Component @Primary
public class FaultInjectingBankAdapter implements BankAdapter {
    public enum FaultMode { NONE, BEFORE_SUBMIT_FAILURE, AFTER_COMMIT_RESPONSE_LOST, PENDING_THEN_SUCCESS, LOOKUP_UNAVAILABLE }
    private final ActionService actions;private final LocalBankExecutionService execution;private final LocalBankAdapter local;private final SimulationService simulation;private final Clock clock;
    public FaultInjectingBankAdapter(ActionService actions,LocalBankExecutionService execution,LocalBankAdapter local,SimulationService simulation,Clock clock){this.actions=actions;this.execution=execution;this.local=local;this.simulation=simulation;this.clock=clock;}
    public boolean enabled(){return simulation.enabled();}
    public void configure(String user,String action,FaultMode submit,FaultMode lookup){simulation.configure(user,action,submit,lookup);}
    public BankObservation submit(String user,String id){return switch(simulation.submitMode(user,id)){
        case BEFORE_SUBMIT_FAILURE->local.observation(id,execution.rejectAuthorized(user,id,"SIMULATED_BANK_REJECTION"));
        case AFTER_COMMIT_RESPONSE_LOST->{local.submit(user,id);yield new BankObservation(id,UNKNOWN,null,"RESPONSE_LOST",clock.instant());}
        case PENDING_THEN_SUCCESS->local.observation(id,execution.acceptPending(user,id));
        default->local.submit(user,id);
    };}
    public BankObservation lookup(String user,String id){actions.status(user,id);
        if(simulation.lookupMode(user,id)==FaultMode.LOOKUP_UNAVAILABLE)return new BankObservation(id,UNKNOWN,null,"LOOKUP_UNAVAILABLE",clock.instant());
        if(simulation.submitMode(user,id)==FaultMode.PENDING_THEN_SUCCESS&&actions.status(user,id).status()==ActionStatus.PENDING)return local.submit(user,id);
        return local.lookup(user,id);
    }
}
