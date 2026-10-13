package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.bank.FaultInjectingBankAdapter.FaultMode;
import cn.safebank.dao.BankFaultMapper;
import cn.safebank.entity.BankFaultEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
@Service public class SimulationService {
    private final ActionService actions;
    private final BankFaultMapper faults;
    private final boolean enabled;
    public SimulationService(ActionService actions,BankFaultMapper faults,@Value("${bank.simulation.faults-enabled:false}") boolean enabled){
        this.actions=actions;
        this.faults=faults;
        this.enabled=enabled;
    }
    public boolean enabled(){
        return enabled;
    }
    public void configure(String userId,String actionId,FaultMode submit,FaultMode lookup){
        if(!enabled)throw new BankException("SIMULATION_DISABLED","故障配置只在显式本地实验模式启用");
        actions.status(userId,actionId);
        if(submit==FaultMode.LOOKUP_UNAVAILABLE||!List.of(FaultMode.NONE,FaultMode.LOOKUP_UNAVAILABLE).contains(lookup))throw new BankException("VALIDATION_ERROR","故障模式位置错误");
        var row=new BankFaultEntity();
        row.setActionId(actionId);
        row.setUserId(userId);
        row.setSubmitMode(submit.name());
        row.setLookupMode(lookup.name());
        if(faults.upsert(row)!=1)throw new IllegalStateException("故障配置保存失败");
    }
    public FaultMode submitMode(String userId,String actionId){
        var row=enabled?faults.selectOwned(userId,actionId):null;
        return row==null?FaultMode.NONE:FaultMode.valueOf(row.getSubmitMode());
    }
    public FaultMode lookupMode(String userId,String actionId){
        var row=enabled?faults.selectOwned(userId,actionId):null;
        return row==null?FaultMode.NONE:FaultMode.valueOf(row.getLookupMode());
    }
}
