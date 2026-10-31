package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dao.*;
import cn.safebank.entity.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
@Service public class RiskDecisionService {
    public static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private final RiskActionContext context;private final ActionMapper actions;private final RiskDecisionMapper decisions;private final ActionPayloadCodec codec;
    private final DailyUsageService usage;private final RiskPolicyService policy;private final RiskEventService events;private final Clock clock;private final boolean demo;
    public RiskDecisionService(ActionMapper actions,RiskDecisionMapper decisions,ActionPayloadCodec codec,DailyUsageService usage,RiskPolicyService policy,RiskEventService events,Clock clock,@Value("${bank.verification.demo-enabled:false}") boolean demo,RiskActionContext context){
        this.context=context;this.actions=actions;this.decisions=decisions;this.codec=codec;this.usage=usage;this.policy=policy;this.events=events;this.clock=clock;this.demo=demo;
    }
    public ActionEntity owned(String user,String id,boolean lock){
        var row=lock?actions.selectOwnedForUpdate(user,id):actions.selectOwned(user,id);
        if(row==null)throw new BankException("NOT_FOUND","资源不存在或不属于当前用户");
        if(!Objects.equals(row.getPayloadHash(),codec.hash(user,codec.kind(row.getKind()),row.getPayload())))throw new BankException("CONFLICT","操作参数完整性校验失败");
        return row;
    }
    public LocalDate date(){return SimulationClock.businessDate(clock);}
    public Instant deadline(Instant now,int minutes){Instant midnight=now.atZone(BUSINESS_ZONE).toLocalDate().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();return now.plusSeconds(minutes*60L).isBefore(midnight)?now.plusSeconds(minutes*60L):midnight;}
    public long amount(ActionEntity row){return codec.kind(row.getKind())==ActionKind.TRANSFER?((TransferParametersVO)codec.decode(ActionKind.TRANSFER,row.getPayload())).amountMinor():0;}
    private RiskEvaluation evaluate(ActionEntity row,Instant now){
        var result=policy.evaluate(codec.kind(row.getKind()),amount(row),usage.effectiveMinor(row.getUserId(),date(),now,row.getId()),date());
        var previous=latest(row.getUserId(),row.getId());
        if(previous!=null&&previous.getValidUntil().toInstant().isAfter(now)&&previous.getBusinessDate().equals(result.businessDate())&&"RED".equals(previous.getRiskLevel())&&result.level()!=RiskLevel.RED)
            return new RiskEvaluation(RiskLevel.RED,AssuranceRequirement.STEP_UP,result.amountMinor(),result.effectiveMinor(),result.projectedMinor(),"PRIOR_STEP_UP_REQUIREMENT",result.businessDate(),result.policyVersion());
        return result;
    }
    public RiskDecisionEntity latest(String user,String action){var list=decisions.selectByAction(user,action);return list.isEmpty()?null:list.getFirst();}
    private boolean matches(RiskDecisionEntity old,ActionEntity row,RiskEvaluation e,Instant now){
        return old!=null&&old.getValidUntil().toInstant().isAfter(now)&&old.getPayloadHash().equals(row.getPayloadHash())&&old.getPolicyVersion().equals(e.policyVersion())&&old.getBusinessDate().equals(e.businessDate())&&old.getRiskLevel().equals(e.level().name())&&old.getEffectiveMinor()==e.effectiveMinor()&&old.getProjectedMinor()==e.projectedMinor();
    }
    public RiskDecisionVO assessLocked(String userId,String actionId,RiskChannel channel){
        var row=owned(userId,actionId,true);var now=clock.instant();var e=evaluate(row,now);var old=latest(userId,actionId);
        if(matches(old,row,e,now))return toVO(old,false);
        var d=new RiskDecisionEntity();d.setId(UUID.randomUUID().toString());d.setUserId(userId);d.setActionId(actionId);d.setRevision(old==null?1L:old.getRevision()+1);d.setPolicyVersion(e.policyVersion());d.setRiskLevel(e.level().name());d.setRequiredAssurance(e.requiredAssurance().name());d.setReasonCode(e.reasonCode());d.setBusinessDate(e.businessDate());d.setAmountMinor(e.amountMinor());d.setEffectiveMinor(e.effectiveMinor());d.setProjectedMinor(e.projectedMinor());d.setPayloadHash(row.getPayloadHash());d.setCreatedAt(now.atOffset(ZoneOffset.UTC));d.setValidUntil(deadline(now,5).atOffset(ZoneOffset.UTC));
        if(decisions.insert(d)!=1)throw new IllegalStateException("Decision insert failed");
        events.record(userId,actionId,d.getId(),"RISK_ASSESSED",d.getReasonCode(),channel);return toVO(d,false);
    }
    public RiskDecisionVO view(String userId,String actionId){return context.inAction(userId,actionId,()->viewCurrent(userId,actionId));}
    private RiskDecisionVO viewCurrent(String userId,String actionId){
        var row=owned(userId,actionId,false);var now=clock.instant();var old=latest(userId,actionId);var e=evaluate(row,now);
        if(matches(old,row,e,now))return toVO(old,false);
        return new RiskDecisionVO(old==null?null:old.getId(),e.policyVersion(),e.level(),e.requiredAssurance(),e.reasonCode(),e.businessDate(),e.effectiveMinor(),e.projectedMinor(),old==null?deadline(now,5):old.getValidUntil().toInstant(),e.level()==RiskLevel.RED?"VERIFY_THEN_CONFIRM":"CONFIRM",demo?"LOCAL_DEMO_ONLY":"EXTERNAL_MFA_REQUIRED",true);
    }
    public RiskDecisionVO toVO(RiskDecisionEntity d,boolean stale){return new RiskDecisionVO(d.getId(),d.getPolicyVersion(),RiskLevel.valueOf(d.getRiskLevel()),AssuranceRequirement.valueOf(d.getRequiredAssurance()),d.getReasonCode(),d.getBusinessDate(),d.getEffectiveMinor(),d.getProjectedMinor(),d.getValidUntil().toInstant(),"RED".equals(d.getRiskLevel())?"VERIFY_THEN_CONFIRM":"CONFIRM",demo?"LOCAL_DEMO_ONLY":"EXTERNAL_MFA_REQUIRED",stale);}
    public ActionVO actionVO(ActionEntity row,RiskDecisionVO security){return new ActionVO(row.getId(),row.getUserId(),codec.kind(row.getKind()),row.getPayloadHash(),ActionStatus.valueOf(row.getStatus()),row.getExpiresAt(),row.getReceiptId(),row.getErrorCode(),codec.decode(codec.kind(row.getKind()),row.getPayload()),security);}
}
