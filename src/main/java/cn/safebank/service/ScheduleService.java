package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.dao.*;
import cn.safebank.entity.ScheduleOccurrenceEntity;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service public class ScheduleService {
    private final ScheduleMapper schedules;private final ScheduleOccurrenceMapper occurrences;private final ActionService actions;private final ActionExecutionService execution;private final RiskUserLockService locks;private final ScheduleAuthorizationService authorization;private final Clock clock;
    public ScheduleService(ScheduleMapper schedules,ScheduleOccurrenceMapper occurrences,ActionService actions,ActionExecutionService execution,RiskUserLockService locks,ScheduleAuthorizationService authorization,Clock clock){this.schedules=schedules;this.occurrences=occurrences;this.actions=actions;this.execution=execution;this.locks=locks;this.authorization=authorization;this.clock=clock;}
    public List<ScheduleVO> list(String user){return schedules.selectByUser(user).stream().map(r->new ScheduleVO(r.getId(),r.getUserId(),r.getSourceId(),r.getRecipientId(),r.getAmountMinor(),r.getDayOfMonth(),r.getFirstDate(),r.getEndDate(),r.getStatus())).toList();}
    public List<ActionVO> runDue(String user,String simulatedDate){
        LocalDate date;try{date=LocalDate.parse(simulatedDate);}catch(Exception e){throw new BankException("VALIDATION_ERROR","模拟日期格式错误");}
        if(!(clock instanceof SimulationClock controlled))throw new BankException("SIMULATION_DISABLED","仅隔离实验时钟支持模拟日期");
        return controlled.inBusinessDay(date,()->runDay(user,date));
    }
    private List<ActionVO> runDay(String user,LocalDate date){
        var result=new ArrayList<ActionVO>();
        for(var schedule:list(user)){
            if(!"ACTIVE".equals(schedule.status())||date.getDayOfMonth()!=schedule.dayOfMonth()||date.isBefore(schedule.firstDate())||date.isAfter(schedule.endDate()))continue;
            String id=locks.withUserLock(user,()->{
                var current=schedules.selectOwnedForUpdate(user,schedule.id());if(current==null)throw new BankException("NOT_FOUND","计划不存在");var prior=occurrences.select(schedule.id(),date);if(prior!=null)return prior.getActionId();
                var a=actions.prepare(user,ActionKind.TRANSFER,new TransferParametersVO(current.getSourceId(),current.getRecipientId(),null,current.getAmountMinor(),"CNY",0L,schedule.id(),date.toString()),false);
                var row=new ScheduleOccurrenceEntity();row.setScheduleId(schedule.id());row.setDueDate(date);row.setActionId(a.id());if(occurrences.insert(row)!=1)throw new IllegalStateException("Occurrence insert failed");return a.id();
            });
            var current=actions.status(user,id);
            if(current.status()==ActionStatus.AWAITING_AUTH){var granted=authorization.authorizeOccurrence(user,schedule.id(),id);if(!granted.accepted()){result.add(actions.status(user,id));continue;}}
            try{result.add(execution.submit(user,id).action());}catch(cn.safebank.common.RiskRejectedException security){result.add(actions.status(user,id));}
        }
        return result;
    }
}
