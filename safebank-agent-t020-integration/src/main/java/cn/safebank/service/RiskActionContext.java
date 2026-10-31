package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.dao.*;
import cn.safebank.vo.TransferParametersVO;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.function.Supplier;
@Service public class RiskActionContext {
    private final Clock clock;private final ActionMapper actions;private final ScheduleOccurrenceMapper occurrences;private final ActionPayloadCodec codec;
    public RiskActionContext(Clock clock,ActionMapper actions,ScheduleOccurrenceMapper occurrences,ActionPayloadCodec codec){this.clock=clock;this.actions=actions;this.occurrences=occurrences;this.codec=codec;}
    public <T> T inAction(String user,String id,Supplier<T> work){
        if(!(clock instanceof SimulationClock controlled)||!controlled.enabled())return work.get();
        var a=actions.selectOwned(user,id);if(a==null||!"TRANSFER".equals(a.getKind()))return work.get();
        var occurrence=occurrences.selectOwnedByAction(user,id);if(occurrence==null)return work.get();
        var p=(TransferParametersVO)codec.decode(ActionKind.TRANSFER,a.getPayload());
        if(!occurrence.getScheduleId().equals(p.scheduleId())||!occurrence.getDueDate().toString().equals(p.occurrenceDate()))throw new BankException("CONFLICT","模拟期次上下文不一致");
        return controlled.inBusinessDay(occurrence.getDueDate(),work);
    }
}
