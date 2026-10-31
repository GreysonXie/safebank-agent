package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.RiskTransferUsageMapper;
import cn.safebank.entity.RiskTransferUsageEntity;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Objects;
@Service public class DailyUsageService {
    private final RiskTransferUsageMapper usage;private final cn.safebank.dao.ExecutionObservationMapper observations;
    public DailyUsageService(RiskTransferUsageMapper usage,cn.safebank.dao.ExecutionObservationMapper observations){this.usage=usage;this.observations=observations;}
    public long effectiveMinor(String userId,LocalDate date,Instant now,String excludedActionId){
        long total=0;
        for(var row:usage.selectByUser(userId)){
            if(Objects.equals(excludedActionId,row.getActionId()))continue;
            if(counts(row,date,now))try{total=Math.addExact(total,row.getAmountMinor());}catch(ArithmeticException overflow){throw new BankException("RISK_AMOUNT_OVERFLOW","累计金额无法安全核验");}
        }
        return total;
    }
    public boolean counts(RiskTransferUsageEntity row,LocalDate date,Instant now){
        var observation=observations.selectOwned(row.getUserId(),row.getActionId());
        boolean unresolved=observation!=null&&java.util.List.of("UNKNOWN","PENDING").contains(observation.getState())&&"COMMITTED".equals(row.getState());
        return unresolved||"SUBMITTED".equals(row.getState())||date.equals(row.getBusinessDate())&&("COMMITTED".equals(row.getState())||"RESERVED".equals(row.getState())&&row.getReservedUntil().toInstant().isAfter(now));
    }
    public void expireUnsubmitted(String userId,Instant now){
        for(var row:usage.selectByUser(userId))if("RESERVED".equals(row.getState())&&!row.getReservedUntil().toInstant().isAfter(now)){
            row.setState("RELEASED");row.setResolvedAt(now.atOffset(ZoneOffset.UTC));row.setUpdatedAt(now.atOffset(ZoneOffset.UTC));
            if(usage.update(row)!=1)throw new IllegalStateException("Reservation release failed");
        }
    }
}
