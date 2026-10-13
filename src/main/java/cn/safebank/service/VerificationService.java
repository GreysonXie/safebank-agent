package cn.safebank.service;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.config.VerificationProperties;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.entity.*;
import cn.safebank.verification.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.security.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
@Service public class VerificationService {
    private final RiskUserLockService locks;private final RiskUserGuardMapper guards;private final VerificationChallengeMapper challenges;
    private final RiskDecisionService decisions;private final RiskEventService events;private final VerificationProvider provider;private final VerificationProperties props;private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public VerificationService(RiskUserLockService locks,RiskUserGuardMapper guards,VerificationChallengeMapper challenges,RiskDecisionService decisions,RiskEventService events,VerificationProvider provider,VerificationProperties props,Clock clock){
        this.locks=locks;this.guards=guards;this.challenges=challenges;this.decisions=decisions;this.events=events;this.provider=provider;this.props=props;this.clock=clock;
    }
    public boolean demo(){return props.isDemoEnabled()&&props.getPepper().length()>=24&&!(provider instanceof UnavailableVerificationProvider);}
    public RiskFailure blocked(String user,String id,RiskDecisionVO security,boolean transfer){
        var guard=guards.selectForUpdate(user);Instant now=clock.instant();
        if(guard.getLockedUntil()!=null&&guard.getLockedUntil().toInstant().isAfter(now))return new RiskFailure("SECURITY_LOCKED","验证错误过多，请稍后再试",id,security,guard.getLockedUntil().toInstant());
        if(transfer&&guard.getReconciliationRequired())return new RiskFailure("RISK_RECONCILIATION_REQUIRED","旧交易日期或金额需核对，余额和查单仍可用",id,security,null);
        return null;
    }
    public <T> RiskOutcome<T> deny(String user,String id,RiskDecisionVO d,String code,String message,RiskChannel channel){
        events.record(user,id,d==null?null:d.decisionId(),"SECURITY_REJECTED",code,channel);
        return RiskOutcome.rejected(new RiskFailure(code,message,id,d,null));
    }
    private String digest(String id,String code){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(props.getPepper().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((id+":"+code).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException("Verification digest unavailable");}
    }
    public RiskOutcome<ChallengeVO> create(String user,String id,ChallengeRequestDTO request,RiskChannel channel){
        final String code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        var prepared=locks.withActionLock(user,id,()->{
            var action=decisions.owned(user,id,true);var d=decisions.assessLocked(user,id,channel);
            var blocked=blocked(user,id,d,ActionKind.TRANSFER.name().equals(action.getKind()));
            if(blocked!=null){events.record(user,id,d.decisionId(),"SECURITY_REJECTED",blocked.code(),channel);return RiskOutcome.<ChallengeVO>rejected(blocked);}
            if(!Objects.equals(request.payloadHash(),action.getPayloadHash())||!Objects.equals(request.decisionId(),d.decisionId()))return this.<ChallengeVO>deny(user,id,d,"RISK_DECISION_STALE","风险条件已变化，请刷新原动作",channel);
            if(!List.of("AWAITING_AUTH","AUTHORIZED").contains(action.getStatus())||d.riskLevel()!=RiskLevel.RED)return this.<ChallengeVO>deny(user,id,d,"CONFLICT","当前动作不需要或不能创建验证挑战",channel);
            if(!demo())return this.<ChallengeVO>deny(user,id,d,"VERIFICATION_UNAVAILABLE","当前未接可信验证服务",channel);
            var guard=guards.selectForUpdate(user);var now=clock.instant();
            if(guard.getLastChallengeAt()!=null&&guard.getLastChallengeAt().toInstant().plusSeconds(60).isAfter(now)){
                events.record(user,id,d.decisionId(),"SECURITY_REJECTED","CHALLENGE_RATE_LIMITED",channel);
                return RiskOutcome.<ChallengeVO>rejected(new RiskFailure("CHALLENGE_RATE_LIMITED","请稍后再发起挑战",id,d,guard.getLastChallengeAt().toInstant().plusSeconds(60)));
            }
            for(var previous:challenges.selectByAction(user,id))if(List.of("PENDING_DELIVERY","SENT","VERIFIED").contains(previous.getStatus())){previous.setStatus("INVALIDATED");require(challenges.update(previous));}
            var c=new VerificationChallengeEntity();c.setId(UUID.randomUUID().toString());c.setActionId(id);c.setUserId(user);c.setDecisionId(d.decisionId());c.setPayloadHash(action.getPayloadHash());c.setMethod("DEMO_OTP");c.setCodeDigest(digest(c.getId(),code));c.setStatus("PENDING_DELIVERY");c.setFailedAttempts(0);c.setCreatedAt(now.atOffset(ZoneOffset.UTC));c.setExpiresAt(decisions.deadline(now,2).atOffset(ZoneOffset.UTC));require(challenges.insert(c));
            guard.setLastChallengeAt(now.atOffset(ZoneOffset.UTC));guard.setUpdatedAt(now.atOffset(ZoneOffset.UTC));guard.setRevision(guard.getRevision()+1);require(guards.update(guard));
            events.record(user,id,d.decisionId(),"CHALLENGE_CREATED","LOCAL_DEMO_ONLY",channel);
            return RiskOutcome.success(new ChallengeVO(c.getId(),id,d.decisionId(),c.getExpiresAt().toInstant(),c.getStatus(),"LOCAL_DEMO_ONLY"));
        });
        if(!prepared.accepted())return prepared;
        var c=prepared.value();DeliveryResult delivery;
        try{delivery=provider.deliver(new VerificationDelivery(c.challengeId(),user,VerificationMethod.DEMO_OTP,code,c.expiresAt()));}catch(RuntimeException failure){delivery=new DeliveryResult(false,null,"VERIFICATION_UNAVAILABLE");}
        final DeliveryResult result=delivery;
        return locks.withActionLock(user,id,()->{
            decisions.owned(user,id,true);var row=challenges.selectOwnedForUpdate(user,c.challengeId());var d=decisions.view(user,id);
            if(!"PENDING_DELIVERY".equals(row.getStatus()))return this.<ChallengeVO>deny(user,id,d,"VERIFICATION_EXPIRED","验证挑战已失效",channel);
            row.setStatus(result.delivered()&&row.getExpiresAt().toInstant().isAfter(clock.instant())?"SENT":"DELIVERY_FAILED");row.setProviderReference(result.providerReference());require(challenges.update(row));
            if(!"SENT".equals(row.getStatus()))return this.<ChallengeVO>deny(user,id,d,"VERIFICATION_UNAVAILABLE","验证服务未完成投递",channel);
            return RiskOutcome.success(new ChallengeVO(row.getId(),id,row.getDecisionId(),row.getExpiresAt().toInstant(),"SENT","LOCAL_DEMO_ONLY"));
        });
    }
    public RiskOutcome<VerificationProofVO> verify(String user,String id,String challengeId,ChallengeVerifyRequestDTO request){
        return locks.withActionLock(user,id,()->{
            var action=decisions.owned(user,id,true);var d=decisions.assessLocked(user,id,RiskChannel.HTTP);var blocked=blocked(user,id,d,ActionKind.TRANSFER.name().equals(action.getKind()));
            if(blocked!=null)return RiskOutcome.rejected(blocked);
            var c=challenges.selectOwnedForUpdate(user,challengeId);var now=clock.instant();
            if(c==null||!id.equals(c.getActionId()))return this.<VerificationProofVO>deny(user,id,d,"VERIFICATION_INVALID","证明不属于当前动作",RiskChannel.HTTP);
            if("CONSUMED".equals(c.getStatus()))return this.<VerificationProofVO>deny(user,id,d,"VERIFICATION_REPLAYED","证明已使用",RiskChannel.HTTP);
            if(!c.getExpiresAt().toInstant().isAfter(now)){c.setStatus("EXPIRED");require(challenges.update(c));return this.<VerificationProofVO>deny(user,id,d,"VERIFICATION_EXPIRED","证明已过期",RiskChannel.HTTP);}
            if(!c.getDecisionId().equals(d.decisionId())||!c.getPayloadHash().equals(action.getPayloadHash()))return this.<VerificationProofVO>deny(user,id,d,"RISK_DECISION_STALE","请刷新原动作风险条件",RiskChannel.HTTP);
            if(!List.of("SENT","VERIFIED").contains(c.getStatus()))return this.<VerificationProofVO>deny(user,id,d,"VERIFICATION_INVALID","挑战不可验证",RiskChannel.HTTP);
            boolean correct=request.code()!=null&&request.code().matches("[0-9]{6}")&&MessageDigest.isEqual(c.getCodeDigest().getBytes(StandardCharsets.UTF_8),digest(c.getId(),request.code()).getBytes(StandardCharsets.UTF_8));
            if(!correct){
                c.setFailedAttempts(c.getFailedAttempts()+1);if(c.getFailedAttempts()>=3)c.setStatus("LOCKED");require(challenges.update(c));
                var g=guards.selectForUpdate(user);
                if(g.getFailureWindowStartedAt()==null||!g.getFailureWindowStartedAt().toInstant().plusSeconds(600).isAfter(now)){g.setFailedVerifications(0);g.setFailureWindowStartedAt(now.atOffset(ZoneOffset.UTC));}
                g.setFailedVerifications(g.getFailedVerifications()+1);if(g.getFailedVerifications()>=5)g.setLockedUntil(now.plusSeconds(600).atOffset(ZoneOffset.UTC));g.setUpdatedAt(now.atOffset(ZoneOffset.UTC));g.setRevision(g.getRevision()+1);require(guards.update(g));
                return this.<VerificationProofVO>deny(user,id,d,"VERIFICATION_INVALID","验证码错误",RiskChannel.HTTP);
            }
            if(!"VERIFIED".equals(c.getStatus())){c.setStatus("VERIFIED");c.setVerifiedAt(now.atOffset(ZoneOffset.UTC));require(challenges.update(c));events.record(user,id,d.decisionId(),"CHALLENGE_VERIFIED","LOCAL_DEMO_ONLY",RiskChannel.HTTP);}
            return RiskOutcome.success(new VerificationProofVO(c.getId(),id,c.getDecisionId(),c.getExpiresAt().toInstant(),"LOCAL_DEMO_ONLY"));
        });
    }
    private static void require(int count){if(count!=1)throw new IllegalStateException("Verification state update failed");}
}
