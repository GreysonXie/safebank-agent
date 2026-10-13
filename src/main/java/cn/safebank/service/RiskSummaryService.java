package cn.safebank.service;
import cn.safebank.dao.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.*;
@Service public class RiskSummaryService {
    private final RiskUserGuardMapper guards;private final RiskTransferUsageMapper usage;private final DailyUsageService daily;private final Clock clock;
    public RiskSummaryService(RiskUserGuardMapper guards,RiskTransferUsageMapper usage,DailyUsageService daily,Clock clock){this.guards=guards;this.usage=usage;this.daily=daily;this.clock=clock;}
    public RiskSummaryVO view(String user){
        var now=clock.instant();var date=now.atZone(RiskDecisionService.BUSINESS_ZONE).toLocalDate();long committed=0,reserved=0,unknown=0;
        for(var row:usage.selectByUser(user))if(daily.counts(row,date,now)){if("SUBMITTED".equals(row.getState())||!date.equals(row.getBusinessDate()))unknown=Math.addExact(unknown,row.getAmountMinor());else if("COMMITTED".equals(row.getState()))committed=Math.addExact(committed,row.getAmountMinor());else reserved=Math.addExact(reserved,row.getAmountMinor());}
        var guard=guards.select(user);
        return new RiskSummaryVO(date,committed,reserved,unknown,guard==null||guard.getLockedUntil()==null?null:guard.getLockedUntil().toInstant(),guard!=null&&guard.getReconciliationRequired());
    }
}
