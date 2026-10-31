package cn.safebank.service;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.entity.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service public class AuthorizationGateService {
    private final RiskUserLockService locks;private final RiskDecisionService decisions;private final VerificationService verification;
    private final ActionMapper actions;private final ActionAuthorizationMapper grants;private final VerificationChallengeMapper challenges;private final RiskTransferUsageMapper usage;private final ExecutionObservationMapper observations;
    private final RiskReconciliationService reconciliation;private final DailyUsageService daily;private final RiskEventService events;private final AuditService audit;private final Clock clock;
    public AuthorizationGateService(RiskUserLockService locks,RiskDecisionService decisions,VerificationService verification,ActionMapper actions,ActionAuthorizationMapper grants,VerificationChallengeMapper challenges,RiskTransferUsageMapper usage,ExecutionObservationMapper observations,DailyUsageService daily,RiskEventService events,AuditService audit,Clock clock,RiskReconciliationService reconciliation){
        this.reconciliation=reconciliation;this.locks=locks;this.decisions=decisions;this.verification=verification;this.actions=actions;this.grants=grants;this.challenges=challenges;this.usage=usage;this.observations=observations;this.daily=daily;this.events=events;this.audit=audit;this.clock=clock;
    }
    public RiskDecisionVO riskView(String user,String id){return decisions.view(user,id);}
    public RiskDecisionVO createDecision(String user,String id,RiskChannel channel){return locks.withActionLock(user,id,()->decisions.assessLocked(user,id,channel));}
    public RiskDecisionVO refreshDecision(String user,String id,RiskChannel channel){return locks.withActionLock(user,id,()->{
        var a=decisions.owned(user,id,true);if(!List.of("AWAITING_AUTH","AUTHORIZED").contains(a.getStatus())||observations.selectOwned(user,id)!=null)throw new cn.safebank.BankException("CONFLICT","已受理动作仅能查单");
        if(!a.getExpiresAt().toInstant().isAfter(clock.instant())){revokeUnsubmitted(user,id,clock.instant());require(actions.updateExpiry(user,id,decisions.deadline(clock.instant(),5).atOffset(ZoneOffset.UTC)));require(actions.updateState(user,id,"AWAITING_AUTH",a.getReceiptId(),null));}
        return decisions.assessLocked(user,id,channel);
    });}
    private <T> RiskOutcome<T> deny(String user,String id,RiskDecisionVO d,String code,String text,RiskChannel channel){return verification.deny(user,id,d,code,text,channel);}
    public RiskOutcome<ActionVO> authorize(String user,String id,AuthorizationRequestDTO request,RiskChannel channel){
        return locks.withActionLock(user,id,()->{
            reconciliation.reconcileUser(user);var row=decisions.owned(user,id,true);var now=clock.instant();daily.expireUnsubmitted(user,now);var d=decisions.assessLocked(user,id,channel);
            var block=verification.blocked(user,id,d,ActionKind.TRANSFER.name().equals(row.getKind()));
            if(block!=null){events.record(user,id,d.decisionId(),"SECURITY_REJECTED",block.code(),channel);return RiskOutcome.rejected(block);}
            if(!List.of("AWAITING_AUTH","AUTHORIZED").contains(row.getStatus())||observations.selectOwned(user,id)!=null)return this.<ActionVO>deny(user,id,d,"CONFLICT","动作已提交或终止，请查原回执",channel);
            if(!Objects.equals(request.payloadHash(),row.getPayloadHash()))return this.<ActionVO>deny(user,id,d,"CONFLICT","确认参数已改变",channel);
            if(!row.getExpiresAt().toInstant().isAfter(now))return this.<ActionVO>deny(user,id,d,"AUTH_EXPIRED","操作已过期，请刷新同一动作后重新确认",channel);
            if(request.decisionId()==null||request.decisionId().isBlank())return this.<ActionVO>deny(user,id,d,"RISK_DECISION_STALE","需要当前风险判定，请刷新原动作",channel);
            if(!request.userConfirmed())return this.<ActionVO>deny(user,id,d,"AUTH_REQUIRED","需要明确确认本次操作",channel);
            var active=active(user,id);
            if(active!=null&&active.getExpiresAt().toInstant().isAfter(now)&&active.getDecisionId().equals(d.decisionId())&&active.getPayloadHash().equals(row.getPayloadHash())&&active.getPolicyVersion().equals(d.policyVersion())&&active.getBusinessDate().equals(d.businessDate())&&Objects.equals(request.decisionId(),d.decisionId()))
                return RiskOutcome.success(decisions.actionVO(row,d));
            if(d.riskLevel()==RiskLevel.RED&&request.verificationProofId()==null){
                revokeUnsubmitted(user,id,now);require(actions.updateState(user,id,"AWAITING_AUTH",row.getReceiptId(),row.getErrorCode()));
                return this.<ActionVO>deny(user,id,d,"STEP_UP_REQUIRED","累计金额需要补充验证，再明确确认",channel);
            }
            if(!Objects.equals(request.decisionId(),d.decisionId()))return this.<ActionVO>deny(user,id,d,"RISK_DECISION_STALE","风险条件已变化，请刷新原动作",channel);
            VerificationChallengeEntity proof=null;
            AuthorizationMethod method=AuthorizationMethod.USER_CONFIRM;AuthorizationAssurance assurance=AuthorizationAssurance.CONFIRMED;
            if(d.riskLevel()==RiskLevel.RED){
                proof=challenges.selectOwnedForUpdate(user,request.verificationProofId());
                if(proof!=null&&"CONSUMED".equals(proof.getStatus()))return this.<ActionVO>deny(user,id,d,"VERIFICATION_REPLAYED","证明已消费",channel);
                if(proof==null||!id.equals(proof.getActionId())||!d.decisionId().equals(proof.getDecisionId())||!row.getPayloadHash().equals(proof.getPayloadHash())||!"VERIFIED".equals(proof.getStatus()))return this.<ActionVO>deny(user,id,d,"VERIFICATION_INVALID","缺少当前动作的有效验证证明",channel);
                if(!proof.getExpiresAt().toInstant().isAfter(now))return this.<ActionVO>deny(user,id,d,"VERIFICATION_EXPIRED","验证证明已过期",channel);
                if(!verification.demo()||!"DEMO_OTP".equals(proof.getMethod()))return this.<ActionVO>deny(user,id,d,"VERIFICATION_UNAVAILABLE","未配置可信验证服务",channel);
                method=AuthorizationMethod.DEMO_OTP;assurance=AuthorizationAssurance.DEMO_VERIFIED;
            }
            revokeUnsubmitted(user,id,now);
            var grant=new ActionAuthorizationEntity();grant.setId(UUID.randomUUID().toString());grant.setUserId(user);grant.setActionId(id);grant.setDecisionId(d.decisionId());grant.setChallengeId(proof==null?null:proof.getId());grant.setPayloadHash(row.getPayloadHash());grant.setPolicyVersion(d.policyVersion());grant.setBusinessDate(d.businessDate());grant.setMethod(method.name());grant.setAssurance(assurance.name());grant.setStatus("ACTIVE");grant.setAuthorizedAt(now.atOffset(ZoneOffset.UTC));grant.setExpiresAt(decisions.deadline(now,5).atOffset(ZoneOffset.UTC));require(grants.insert(grant));
            if(proof!=null){proof.setStatus("CONSUMED");require(challenges.update(proof));}
            if(ActionKind.TRANSFER.name().equals(row.getKind())){
                var reservation=usage.selectOwnedForUpdate(user,id);boolean insert=reservation==null;if(insert)reservation=new RiskTransferUsageEntity();
                reservation.setActionId(id);reservation.setUserId(user);reservation.setAuthorizationId(grant.getId());reservation.setProvenance("CURRENT_AUTHORIZATION");reservation.setBusinessDate(d.businessDate());reservation.setAmountMinor(decisions.amount(row));reservation.setState("RESERVED");reservation.setReservedUntil(grant.getExpiresAt());reservation.setSubmittedAt(null);reservation.setResolvedAt(null);reservation.setUpdatedAt(now.atOffset(ZoneOffset.UTC));require(insert?usage.insert(reservation):usage.update(reservation));
            }
            require(actions.updateState(user,id,"AUTHORIZED",row.getReceiptId(),null));row.setStatus("AUTHORIZED");row.setErrorCode(null);audit.record(user,id,"AUTHORIZATION_GRANTED");events.record(user,id,d.decisionId(),"AUTHORIZATION_GRANTED",assurance.name(),channel);
            return RiskOutcome.success(decisions.actionVO(row,d));
        });
    }
    public ActionAuthorizationEntity started(String user,String id){return grants.selectByAction(user,id).stream().filter(g->"SUBMISSION_STARTED".equals(g.getStatus())).findFirst().orElseThrow(()->new cn.safebank.BankException("AUTH_REQUIRED","缺少受理授权"));}
    public ActionAuthorizationEntity active(String user,String id){return grants.selectByAction(user,id).stream().filter(g->"ACTIVE".equals(g.getStatus())).findFirst().orElse(null);}
    private void revokeUnsubmitted(String user,String id,Instant now){
        for(var grant:grants.selectByAction(user,id))if("ACTIVE".equals(grant.getStatus())){grant.setStatus(grant.getExpiresAt().toInstant().isAfter(now)?"REVOKED":"EXPIRED");require(grants.update(grant));}
        var u=usage.selectOwnedForUpdate(user,id);if(u!=null&&"RESERVED".equals(u.getState())){u.setState("RELEASED");u.setResolvedAt(now.atOffset(ZoneOffset.UTC));u.setUpdatedAt(now.atOffset(ZoneOffset.UTC));require(usage.update(u));}
    }
    public RiskOutcome<ActionVO> cancel(String user,String id,RiskChannel channel){
        return locks.withActionLock(user,id,()->{
            var row=decisions.owned(user,id,true);var d=decisions.assessLocked(user,id,channel);var block=verification.blocked(user,id,d,false);if(block!=null)return RiskOutcome.rejected(block);
            var u=usage.selectOwnedForUpdate(user,id);
            if(!List.of("AWAITING_AUTH","AUTHORIZED").contains(row.getStatus())||observations.selectOwned(user,id)!=null||u!=null&&"SUBMITTED".equals(u.getState()))return this.<ActionVO>deny(user,id,d,"CONFLICT","已受理动作只能查单",channel);
            revokeUnsubmitted(user,id,clock.instant());for(var c:challenges.selectByAction(user,id))if(List.of("SENT","VERIFIED","PENDING_DELIVERY").contains(c.getStatus())){c.setStatus("INVALIDATED");require(challenges.update(c));}
            require(actions.updateState(user,id,"CANCELLED",row.getReceiptId(),row.getErrorCode()));row.setStatus("CANCELLED");audit.record(user,id,"CANCELLED");events.record(user,id,d.decisionId(),"ACTION_CANCELLED",null,channel);
            return RiskOutcome.success(decisions.actionVO(row,d));
        });
    }
    public RiskOutcome<SubmissionPermit> beginSubmission(String user,String id,RiskChannel channel){
        return locks.withActionLock(user,id,()->{
            var row=decisions.owned(user,id,true);
            if(observations.selectOwned(user,id)!=null)return RiskOutcome.success(new SubmissionPermit(id,null,SubmissionPermit.Mode.LOOKUP_ONLY));
            var now=clock.instant();var d=decisions.assessLocked(user,id,channel);var grant=active(user,id);
            var block=verification.blocked(user,id,d,ActionKind.TRANSFER.name().equals(row.getKind()));if(block!=null)return RiskOutcome.rejected(block);
            if(!"AUTHORIZED".equals(row.getStatus())||grant==null)return this.<SubmissionPermit>deny(user,id,d,"AUTH_REQUIRED","提交前需要当前动作的有效授权",channel);
            if(!row.getExpiresAt().toInstant().isAfter(now)||!grant.getExpiresAt().toInstant().isAfter(now)||!grant.getBusinessDate().equals(d.businessDate())){revokeUnsubmitted(user,id,now);require(actions.updateState(user,id,"AWAITING_AUTH",row.getReceiptId(),null));return this.<SubmissionPermit>deny(user,id,d,"AUTH_EXPIRED","授权已过期，请重新确认原动作",channel);}
            if(!grant.getPayloadHash().equals(row.getPayloadHash())||!grant.getPolicyVersion().equals(d.policyVersion())||!grant.getDecisionId().equals(d.decisionId())||d.riskLevel()==RiskLevel.RED&&"CONFIRMED".equals(grant.getAssurance())){
                revokeUnsubmitted(user,id,now);require(actions.updateState(user,id,"AWAITING_AUTH",row.getReceiptId(),null));return this.<SubmissionPermit>deny(user,id,d,d.riskLevel()==RiskLevel.RED?"STEP_UP_REQUIRED":"RISK_DECISION_STALE","提交条件已变化，请刷新并确认原动作",channel);
            }
            grant.setStatus("SUBMISSION_STARTED");require(grants.update(grant));
            if(ActionKind.QUERY_MANDATES.name().equals(row.getKind()))return RiskOutcome.success(new SubmissionPermit(id,grant.getId(),SubmissionPermit.Mode.READ_QUERY));
            var u=usage.selectOwnedForUpdate(user,id);if(ActionKind.TRANSFER.name().equals(row.getKind())&&(u==null||!"RESERVED".equals(u.getState())||!grant.getId().equals(u.getAuthorizationId())))throw new IllegalStateException("Transfer reservation missing");
            if(u!=null){u.setState("SUBMITTED");u.setSubmittedAt(now.atOffset(ZoneOffset.UTC));u.setUpdatedAt(now.atOffset(ZoneOffset.UTC));require(usage.update(u));}
            persistObservation(user,new cn.safebank.bank.BankObservation(id,cn.safebank.bank.BankObservation.BankState.UNKNOWN,null,"SUBMISSION_STARTED",now));
            events.record(user,id,d.decisionId(),"BANK_SUBMISSION_STARTED",null,channel);
            return RiskOutcome.success(new SubmissionPermit(id,grant.getId(),SubmissionPermit.Mode.NEW_BANK_SUBMISSION));
        });
    }
    public void requireBankAcceptance(String user,String id){
        var action=decisions.owned(user,id,true);
        if(ActionKind.QUERY_MANDATES.name().equals(action.getKind()))throw new cn.safebank.BankException("CONFLICT","查询不能调用资金适配器");
        var observation=observations.selectOwned(user,id);
        var accepted=grants.selectByAction(user,id).stream().filter(g->List.of("SUBMISSION_STARTED","CONSUMED").contains(g.getStatus())&&g.getPayloadHash().equals(action.getPayloadHash())).findFirst().orElse(null);
        var legacy=usage.selectOwnedForUpdate(user,id);
        boolean trustedLegacy=legacy!=null&&"LEGACY_OBSERVED".equals(legacy.getProvenance())&&"SUBMITTED".equals(legacy.getState())&&"PENDING".equals(action.getStatus())&&observation!=null&&List.of("PENDING","UNKNOWN").contains(observation.getState());
        if(trustedLegacy)return;
        if(observation==null||accepted==null)throw new cn.safebank.BankException("AUTH_REQUIRED","缺少统一门禁的银行受理证据");
        grants.selectOwnedForUpdate(user,accepted.getId());usage.selectOwnedForUpdate(user,id);
    }
    public void consumeQuery(String user,String id){
        var grant=grants.selectByAction(user,id).stream().filter(g->"SUBMISSION_STARTED".equals(g.getStatus())).findFirst().orElseThrow(()->new IllegalStateException("Query grant missing"));
        grant.setStatus("CONSUMED");require(grants.update(grant));audit.record(user,id,"CONFIRMED_QUERY_EXECUTED");events.record(user,id,grant.getDecisionId(),"QUERY_EXECUTED",null,RiskChannel.HTTP);
    }
    public void completeAccepted(String user,String id,cn.safebank.bank.BankObservation observed){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Bank completion requires the owning transaction");
        var action=decisions.owned(user,id,true);var u=usage.selectOwnedForUpdate(user,id);var now=clock.instant();
        if("NOT_SUBMITTED".equals(observed.errorCode())){
            if(List.of("VERIFIED_SUCCESS","VERIFIED_FAILED","PENDING").contains(action.getStatus()))throw new cn.safebank.BankException("CONFLICT","已受理动作不能回退为未提交");
            for(var grant:grants.selectByAction(user,id))if(java.util.List.of("ACTIVE","SUBMISSION_STARTED").contains(grant.getStatus())){grant.setStatus("REVOKED");require(grants.update(grant));}
            if(u!=null){u.setState("RELEASED");u.setResolvedAt(now.atOffset(ZoneOffset.UTC));u.setUpdatedAt(now.atOffset(ZoneOffset.UTC));require(usage.update(u));}
            require(observations.deleteOwned(user,id));require(actions.updateState(user,id,"AWAITING_AUTH",null,null));events.record(user,id,null,"BANK_NOT_SUBMITTED",null,RiskChannel.SYSTEM);return;
        }
        boolean terminal=observed.state()==cn.safebank.bank.BankObservation.BankState.VERIFIED_SUCCESS||observed.state()==cn.safebank.bank.BankObservation.BankState.VERIFIED_FAILED;
        if(u!=null){boolean unknownLegacyDate="LEGACY_OBSERVED".equals(u.getProvenance())&&u.getBusinessDate()==null;u.setState(terminal&&!unknownLegacyDate?(observed.state()==cn.safebank.bank.BankObservation.BankState.VERIFIED_SUCCESS?"COMMITTED":"RELEASED"):"SUBMITTED");u.setResolvedAt(terminal&&!unknownLegacyDate?now.atOffset(ZoneOffset.UTC):null);u.setUpdatedAt(now.atOffset(ZoneOffset.UTC));require(usage.update(u));}
        if(terminal)for(var grant:grants.selectByAction(user,id))if("SUBMISSION_STARTED".equals(grant.getStatus())){grant.setStatus("CONSUMED");require(grants.update(grant));}
        persistObservation(user,observed);
    }
    private void persistObservation(String user,cn.safebank.bank.BankObservation observed){
        var e=new ExecutionObservationEntity();e.setActionId(observed.actionId());e.setUserId(user);e.setState(observed.state().name());e.setReceiptId(observed.receiptId());e.setErrorCode(observed.errorCode());e.setObservedAt(observed.observedAt().atOffset(ZoneOffset.UTC));require(observations.upsert(e));
    }
    private static void require(int count){if(count!=1)throw new IllegalStateException("Authorization write failed");}
}
