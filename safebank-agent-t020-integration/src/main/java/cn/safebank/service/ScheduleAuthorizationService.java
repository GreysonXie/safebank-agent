package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dao.*;
import cn.safebank.entity.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.*;
@Service public class ScheduleAuthorizationService {
    @org.springframework.beans.factory.annotation.Autowired private ScheduleOccurrenceMapper occurrences;
    private final ScheduleAuthorizationMapper scopes;private final ScheduleMapper schedules;private final ActionAuthorizationMapper grants;private final RiskUserLockService locks;private final AuthorizationGateService gate;private final RiskDecisionService decisions;private final ActionPayloadCodec codec;private final ObjectMapper json;private final Clock clock;private final RiskEventService events;
    public ScheduleAuthorizationService(ScheduleAuthorizationMapper scopes,ScheduleMapper schedules,ActionAuthorizationMapper grants,RiskUserLockService locks,AuthorizationGateService gate,RiskDecisionService decisions,ActionPayloadCodec codec,ObjectMapper json,Clock clock,RiskEventService events){
        this.scopes=scopes;this.schedules=schedules;this.grants=grants;this.locks=locks;this.gate=gate;this.decisions=decisions;this.codec=codec;this.json=json;this.clock=clock;this.events=events;
    }
    public void registerScope(String user,String scheduleId,String sourceAuthorizationId,ScheduleScope scope){
        var grant=grants.selectOwned(user,sourceAuthorizationId);
        if(grant==null||!java.util.List.of("SUBMISSION_STARTED","CONSUMED").contains(grant.getStatus()))throw new BankException("AUTH_REQUIRED","计划登记缺少当前授权");
        var a=decisions.owned(user,grant.getActionId(),false);
        boolean management=ActionKind.MANAGE_SCHEDULE.name().equals(a.getKind());
        if((!ActionKind.SCHEDULE.name().equals(a.getKind())&&!management)||!RiskPolicyService.VERSION.equals(scope.policyVersion())||scope.maximumRiskLevel()!=RiskLevel.YELLOW||!"CNY".equals(scope.currency())||!"MONTHLY".equals(scope.recurrence()))throw new BankException("CONFLICT","委托范围无效");
        ScheduleParametersVO p;
        if(management){var m=(ScheduleManagementParametersVO)codec.decode(ActionKind.MANAGE_SCHEDULE,a.getPayload());if(!scheduleId.equals(m.scheduleId())||!java.util.Set.of("EDIT","RESUME").contains(m.operation()))throw new BankException("CONFLICT","修改授权与计划不匹配");p=new ScheduleParametersVO(m.sourceId(),m.recipientId(),m.amountMinor(),m.firstDate(),m.endDate(),m.dayOfMonth(),m.timezone(),"本次失败，不补扣");}
        else p=(ScheduleParametersVO)codec.decode(ActionKind.SCHEDULE,a.getPayload());
        if(!scope.sourceId().equals(p.sourceId())||!scope.recipientId().equals(p.recipientId())||scope.amountMinor()!=p.amountMinor()||!scope.firstDate().toString().equals(p.firstDate())||!scope.endDate().toString().equals(p.endDate())||scope.dayOfMonth()!=p.dayOfMonth()||!scope.timezone().equals(p.timezone()))throw new BankException("CONFLICT","委托范围与原计划不一致");
        try{
            String payload=json.writeValueAsString(scope);var row=new ScheduleAuthorizationEntity();row.setScheduleId(scheduleId);row.setUserId(user);row.setSourceAuthorizationId(sourceAuthorizationId);row.setScopeJson(payload);row.setScopeHash(codec.hash(user,ActionKind.SCHEDULE,payload));row.setPolicyVersion(scope.policyVersion());row.setMaximumRiskLevel("YELLOW");row.setStatus("ACTIVE");row.setCreatedAt(OffsetDateTime.now(clock));row.setExpiresAt(scope.endDate().plusDays(1).atStartOfDay(ZoneId.of(scope.timezone())).toOffsetDateTime());
            if((scopes.selectOwnedForUpdate(user,scheduleId)==null?scopes.insert(row):scopes.replace(row))!=1)throw new IllegalStateException("Schedule scope write failed");
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Schedule scope encoding failed");}
    }
    public void suspend(String user,String id){var row=scopes.selectOwnedForUpdate(user,id);if(row!=null){row.setStatus("REVOKED");if(scopes.update(row)!=1)throw new IllegalStateException("Schedule scope suspend failed");}}
    /** Withdraw only unsubmitted occurrences. Accepted or uncertain transfers retain their original identity. */
    public void invalidateUnsubmitted(String user,String scheduleId){
        for(var occurrence:occurrences.selectByUser(user))if(scheduleId.equals(occurrence.getScheduleId())){
            var action=decisions.owned(user,occurrence.getActionId(),true);
            if(java.util.List.of("AWAITING_AUTH","AUTHORIZED").contains(action.getStatus()))gate.cancel(user,action.getId(),RiskChannel.HTTP);
        }
    }
    public RiskOutcome<ActionVO> authorizeOccurrence(String user,String scheduleId,String id){
        return locks.withUserLock(user,()->{
            var schedule=schedules.selectOwnedForUpdate(user,scheduleId);var action=decisions.owned(user,id,true);var d=decisions.assessLocked(user,id,RiskChannel.SCHEDULER);var scope=scopes.selectOwnedForUpdate(user,scheduleId);
            if(schedule==null||scope==null||!"ACTIVE".equals(schedule.getStatus())||!"ACTIVE".equals(scope.getStatus())||!scope.getExpiresAt().toInstant().isAfter(clock.instant()))return RiskOutcome.rejected(new RiskFailure("SCHEDULE_CONFIRMATION_REQUIRED","计划需重新确认",id,d,null));
            try{
                var s=json.readValue(scope.getScopeJson(),ScheduleScope.class);var p=(TransferParametersVO)codec.decode(ActionKind.TRANSFER,action.getPayload());
                boolean matches=codec.hash(user,ActionKind.SCHEDULE,scope.getScopeJson()).equals(scope.getScopeHash())&&RiskPolicyService.VERSION.equals(scope.getPolicyVersion())&&s.maximumRiskLevel()==RiskLevel.YELLOW&&"CNY".equals(s.currency())&&"MONTHLY".equals(s.recurrence())&&"Asia/Shanghai".equals(s.timezone())&&s.policyVersion().equals(scope.getPolicyVersion())&&scheduleId.equals(p.scheduleId())&&s.sourceId().equals(p.sourceId())&&s.recipientId().equals(p.recipientId())&&s.amountMinor()==p.amountMinor()&&s.sourceId().equals(schedule.getSourceId())&&s.recipientId().equals(schedule.getRecipientId())&&s.amountMinor()==schedule.getAmountMinor()&&s.firstDate().equals(schedule.getFirstDate())&&s.endDate().equals(schedule.getEndDate())&&s.dayOfMonth()==schedule.getDayOfMonth();
                var occurrence=LocalDate.parse(p.occurrenceDate());matches=matches&&!occurrence.isBefore(s.firstDate())&&!occurrence.isAfter(s.endDate())&&ScheduleRules.isDue(occurrence,s.dayOfMonth())&&occurrence.equals(decisions.date());
                if(!matches)return RiskOutcome.rejected(new RiskFailure("SCHEDULE_SCOPE_MISMATCH","期次超出已确认范围",id,d,null));
            }catch(Exception e){return RiskOutcome.rejected(new RiskFailure("SCHEDULE_SCOPE_MISMATCH","委托证据无法核验",id,d,null));}
            if(d.riskLevel()==RiskLevel.RED){events.record(user,id,d.decisionId(),"SCHEDULE_PAUSED","STEP_UP_REQUIRED",RiskChannel.SCHEDULER);return RiskOutcome.rejected(new RiskFailure("STEP_UP_REQUIRED","本期需要验证并确认原动作",id,d,null));}
            var result=gate.authorize(user,id,new AuthorizationRequestDTO(action.getPayloadHash(),d.decisionId(),true,null),RiskChannel.SCHEDULER);
            if(result.accepted()){var grant=gate.active(user,id);if("USER_CONFIRM".equals(grant.getMethod())){if(grants.bindScheduleDelegation(user,grant.getId())!=1)throw new IllegalStateException("Delegation binding failed");events.record(user,id,d.decisionId(),"SCHEDULE_SCOPE_AUTHORIZED",scheduleId,RiskChannel.SCHEDULER);}else if(!"SCHEDULE_DELEGATION".equals(grant.getMethod()))throw new IllegalStateException("Unexpected delegation method");}
            return result;
        });
    }
}
