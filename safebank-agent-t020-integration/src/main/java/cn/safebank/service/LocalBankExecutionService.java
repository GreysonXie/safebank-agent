package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.dao.*;
import cn.safebank.entity.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
@Service public class LocalBankExecutionService {
    private final ActionService actions;
    private final AccountMapper accounts;
    private final LedgerEntryMapper ledger;
    private final TransactionMapper transactions;
    private final MandateMapper mandates;
    private final ScheduleMapper schedules;
    private final RecipientService recipients;
    private final TransferValidationService validation;
    private final ActionPayloadCodec codec;
    private final AuditService audit;
    private final Clock clock;private final org.springframework.beans.factory.ObjectProvider<AgentMandateReceiptService> agentReceipts;
    private final ScheduleAuthorizationService scopeService;
    private final AuthorizationGateService gate;private final RiskUserLockService riskLocks;
    private final TransactionTemplate tx;
    public LocalBankExecutionService(ActionService actions,AccountMapper accounts,LedgerEntryMapper ledger,TransactionMapper transactions,MandateMapper mandates,ScheduleMapper schedules,RecipientService recipients,TransferValidationService validation,ActionPayloadCodec codec,AuditService audit,Clock clock,PlatformTransactionManager manager,AuthorizationGateService gate,RiskUserLockService riskLocks,ScheduleAuthorizationService scopeService,org.springframework.beans.factory.ObjectProvider<AgentMandateReceiptService> agentReceipts){
        this.actions=actions;
        this.accounts=accounts;
        this.ledger=ledger;
        this.transactions=transactions;
        this.mandates=mandates;
        this.schedules=schedules;
        this.recipients=recipients;
        this.validation=validation;
        this.codec=codec;
        this.audit=audit;
        this.clock=clock;this.agentReceipts=agentReceipts;this.scopeService=scopeService;this.gate=gate;this.riskLocks=riskLocks;
        this.tx=new TransactionTemplate(manager);
    }
    public ActionVO submit(String userId,String id){
        return riskLocks.withActionLock(userId,id,()->{
            gate.requireBankAcceptance(userId,id);var a=actions.stored(userId,id,true); if(List.of("VERIFIED_SUCCESS","VERIFIED_FAILED").contains(a.getStatus()))return actions.status(userId,id); if(!List.of("AUTHORIZED","PENDING").contains(a.getStatus()))throw new BankException("AUTH_REQUIRED","需要用户在操作预览上授权");  String receipt=UUID.randomUUID().toString();var kind=codec.kind(a.getKind());var p=codec.decode(kind,a.getPayload()); switch(kind){
                case QUERY_MANDATES->throw new BankException("CONFLICT","查询不能进入资金执行");
                case TRANSFER->transfer(userId,id,(TransferParametersVO)p); case REVOKE->{
                    var r=(RevokeParametersVO)p;var m=mandates.lockOwned(userId,r.mandateId()); if(m==null)throw new BankException("NOT_FOUND","代扣不存在或不属于当前用户"); if(!"ACTIVE".equals(m.getStatus()))throw new BankException("ALREADY_REVOKED","该代扣已被其他动作撤销，本动作没有新增节省"); require(mandates.revoke(userId,r.mandateId()));agentReceipts.ifAvailable(receipts->receipts.invalidateUser(userId));
                }
                case SCHEDULE->{
                    var r=(ScheduleParametersVO)p;if(LocalDate.parse(r.firstDate()).isBefore(SimulationClock.businessDate(clock)))throw new BankException("VALIDATION_ERROR","首次日期已过期，请重新生成计划预览");validation.validate(userId,r.sourceId(),r.recipientId(),r.amountMinor()); var row=new ScheduleEntity();row.setId(receipt);row.setUserId(userId);row.setSourceId(r.sourceId());row.setRecipientId(r.recipientId());row.setAmountMinor(r.amountMinor());row.setDayOfMonth(r.dayOfMonth());row.setFirstDate(LocalDate.parse(r.firstDate()));row.setEndDate(LocalDate.parse(r.endDate()));row.setStatus("ACTIVE");require(schedules.insert(row));var sourceGrant=gate.started(userId,id);
                    scopeService.registerScope(userId,receipt,sourceGrant.getId(),new ScheduleScope(r.sourceId(),r.recipientId(),r.amountMinor(),"CNY",row.getFirstDate(),row.getEndDate(),r.dayOfMonth(),r.timezone(),"MONTHLY",RiskPolicyService.VERSION,cn.safebank.common.RiskTypes.RiskLevel.YELLOW));
                }
                case ACTIVATE_MANDATE->{
                    var r=(MandateActivationParametersVO)p;
                    if(mandates.selectByUser(userId).stream().anyMatch(m->"ACTIVE".equals(m.getStatus())&&r.merchant().equalsIgnoreCase(m.getMerchant())))throw new BankException("VALIDATION_ERROR","该服务已经有有效代扣授权");
                    if(LocalDate.parse(r.renewalDate()).isBefore(SimulationClock.businessDate(clock)))throw new BankException("VALIDATION_ERROR","扣费日期已过期，请重新生成预览");
                    if(r.reactivation()){
                        var m=mandates.lockOwned(userId,r.mandateId());
                        if(m==null||!"REVOKED".equals(m.getStatus())||!r.merchant().equals(m.getMerchant())||r.chargeAmountMinor()!=m.getAmountMinor()||!r.billingInterval().equals(m.getBillingInterval()))throw new BankException("VALIDATION_ERROR","原代扣服务已变化，请重新核对");
                        require(mandates.activate(userId,r.mandateId(),LocalDate.parse(r.renewalDate())));
                    }else{
                        var row=new MandateEntity();row.setId(r.mandateId());row.setUserId(userId);row.setMerchant(r.merchant());row.setAmountMinor(r.chargeAmountMinor());row.setRenewalDate(LocalDate.parse(r.renewalDate()));row.setStatus("ACTIVE");row.setContractStatus("UNKNOWN");row.setBillingInterval(r.billingInterval());require(mandates.insert(row));
                    }
                    agentReceipts.ifAvailable(receipts->receipts.invalidateUser(userId));
                }
                case MANAGE_SCHEDULE->{
                    var r=(ScheduleManagementParametersVO)p;var row=schedules.selectOwnedForUpdate(userId,r.scheduleId());
                    if(row==null||!codec.hash(userId,ActionKind.MANAGE_SCHEDULE,ScheduleRules.snapshot(row)).equals(r.expectedStateHash()))throw new BankException("VALIDATION_ERROR","计划已被修改，请刷新并重新确认");
                    if("EDIT".equals(r.operation())){
                        validation.validate(userId,r.sourceId(),r.recipientId(),r.amountMinor());
                        if(LocalDate.parse(r.firstDate()).isBefore(SimulationClock.businessDate(clock)))throw new BankException("VALIDATION_ERROR","新的首次日期已过期，请重新核对");
                        row.setSourceId(r.sourceId());row.setRecipientId(r.recipientId());row.setAmountMinor(r.amountMinor());row.setFirstDate(LocalDate.parse(r.firstDate()));row.setEndDate(LocalDate.parse(r.endDate()));row.setDayOfMonth(r.dayOfMonth());
                    }else row.setStatus(switch(r.operation()){case "PAUSE"->"PAUSED";case "RESUME"->"ACTIVE";case "CANCEL"->"CANCELLED";default->throw new BankException("VALIDATION_ERROR","无效计划操作");});
                    scopeService.invalidateUnsubmitted(userId,row.getId());
                    require(schedules.update(row));
                    if("ACTIVE".equals(row.getStatus())){
                        var sourceGrant=gate.started(userId,id);
                        scopeService.registerScope(userId,row.getId(),sourceGrant.getId(),new ScheduleScope(row.getSourceId(),row.getRecipientId(),row.getAmountMinor(),"CNY",row.getFirstDate(),row.getEndDate(),row.getDayOfMonth(),"Asia/Shanghai","MONTHLY",RiskPolicyService.VERSION,cn.safebank.common.RiskTypes.RiskLevel.YELLOW));
                    }else scopeService.suspend(userId,row.getId());
                }
            }
            actions.updateState(userId,id,ActionStatus.VERIFIED_SUCCESS,receipt,a.getErrorCode());audit.record(userId,id,"EXECUTED");gate.completeAccepted(userId,id,new cn.safebank.bank.BankObservation(id,cn.safebank.bank.BankObservation.BankState.VERIFIED_SUCCESS,receipt,null,clock.instant()));return actions.status(userId,id);
        }
        );
    }
    private void require(int count){
        if(count!=1)throw new BankException("CONFLICT","银行记录更新失败");
    }
    private void transfer(String userId,String id,TransferParametersVO p){
        validation.validate(userId,p.sourceId(),p.recipientId(),p.amountMinor());
        String target=recipients.owned(userId,p.recipientId()).getAccountId();
        var locks=new ArrayList<>(List.of(p.sourceId(),target));
        Collections.sort(locks);
        for(String account:locks)if(accounts.lockById(account)==null)throw new BankException("NOT_FOUND","账户不存在");
        validation.validate(userId,p.sourceId(),p.recipientId(),p.amountMinor());
        require(accounts.debit(p.sourceId(),p.amountMinor()));
        require(accounts.credit(target,p.amountMinor()));
        entry(id,p.sourceId(),-p.amountMinor());
        entry(id,target,p.amountMinor());
        LocalDate booked=SimulationClock.businessDate(clock);
        String targetUser=accounts.selectById(target).getUserId();
        String category=userId.equals(targetUser)?"内部转账":"转账";
        transaction(id+":debit",userId,p.sourceId(),"转账 / "+p.recipientId(),category,-p.amountMinor(),booked);
        transaction(id+":credit",targetUser,target,"转账 / "+p.sourceId(),category,p.amountMinor(),booked);
    }
    private void entry(String action,String account,long amount){
        var row=new LedgerEntryEntity();
        row.setId(UUID.randomUUID().toString());
        row.setActionId(action);
        row.setAccountId(account);
        row.setAmountMinor(amount);
        require(ledger.insert(row));
    }
    private void transaction(String id,String user,String account,String merchant,String category,long amount,LocalDate date){
        var row=new TransactionEntity();
        row.setId(id);
        row.setUserId(user);
        row.setAccountId(account);
        row.setMerchant(merchant);
        row.setCategory(category);
        row.setAmountMinor(amount);
        row.setBookedOn(date);
        require(transactions.insert(row));
    }
    public ActionVO acceptPending(String userId,String id){
        return riskLocks.withActionLock(userId,id,()->{
            gate.requireBankAcceptance(userId,id);var a=actions.stored(userId,id,true);if(!"AUTHORIZED".equals(a.getStatus()))throw new BankException("AUTH_REQUIRED","银行受理前需要有效授权");actions.updateState(userId,id,ActionStatus.PENDING,a.getReceiptId(),a.getErrorCode());audit.record(userId,id,"BANK_ACCEPTED");return actions.status(userId,id);
        }
        );
    }
    public ActionVO rejectAuthorized(String userId,String id,String code){
        return reject(userId,id,code,true);
    }
    public ActionVO rejectUnaccepted(String userId,String id,String code){
        return reject(userId,id,code,false);
    }
    private ActionVO reject(String userId,String id,String code,boolean expiry){
        return riskLocks.withActionLock(userId,id,()->{
            gate.requireBankAcceptance(userId,id);var a=actions.stored(userId,id,true);
            // A concurrent runner may already have recorded the same original action's outcome.
            // Reuse it under the action lock; never overwrite evidence or duplicate rejection audit.
            if(!expiry&&List.of("VERIFIED_SUCCESS","VERIFIED_FAILED").contains(a.getStatus()))
                return actions.status(userId,id);
            if(!"AUTHORIZED".equals(a.getStatus()))
                throw new BankException(expiry?"AUTH_REQUIRED":"CONFLICT","只能记录尚未受理动作的确定失败");
            
            actions.updateState(userId,id,ActionStatus.VERIFIED_FAILED,a.getReceiptId(),code);
            audit.record(userId,id,"BANK_REJECTED");gate.completeAccepted(userId,id,new cn.safebank.bank.BankObservation(id,cn.safebank.bank.BankObservation.BankState.VERIFIED_FAILED,a.getReceiptId(),code,clock.instant()));
            return actions.status(userId,id);
        }
        );
    }
    public ActionVO completePending(String userId,String id){
        try{
            return submit(userId,id);
        }
        catch(BankException e){
            if(!List.of("INSUFFICIENT_FUNDS","LIMIT_EXCEEDED","ALREADY_REVOKED","VALIDATION_ERROR").contains(e.code))throw e;
            return riskLocks.withActionLock(userId,id,()->{
                gate.requireBankAcceptance(userId,id);var a=actions.stored(userId,id,true);if(!"PENDING".equals(a.getStatus()))return actions.status(userId,id);actions.updateState(userId,id,ActionStatus.VERIFIED_FAILED,a.getReceiptId(),e.code);audit.record(userId,id,"BANK_PROCESSING_FAILED");gate.completeAccepted(userId,id,new cn.safebank.bank.BankObservation(id,cn.safebank.bank.BankObservation.BankState.VERIFIED_FAILED,a.getReceiptId(),e.code,clock.instant()));return actions.status(userId,id);
            }
            );
        }
    }
}
