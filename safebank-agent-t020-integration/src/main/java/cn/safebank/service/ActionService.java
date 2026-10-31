package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.entity.ActionEntity;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
@Service public class ActionService {
    private final ActionMapper actions;
    private final AccountMapper accounts;
    private final ScheduleMapper schedules;
    private final RecipientService recipients;
    private final MandateService mandates;
    private final TransferValidationService validation;
    private final ActionPayloadCodec codec;
    private final LedgerEntryMapper ledger;
    private final AuditService audit;
    private final Clock clock;
    private final AuthorizationGateService gate;
    private final RiskUserLockService riskLocks;
    private final TransactionTemplate tx;
    public ActionService(ActionMapper actions,AccountMapper accounts,ScheduleMapper schedules,RecipientService recipients,MandateService mandates,TransferValidationService validation,ActionPayloadCodec codec,LedgerEntryMapper ledger,AuditService audit,Clock clock,PlatformTransactionManager manager,AuthorizationGateService gate,RiskUserLockService riskLocks){
        this.actions=actions;
        this.accounts=accounts;
        this.schedules=schedules;
        this.recipients=recipients;
        this.mandates=mandates;
        this.validation=validation;
        this.codec=codec;
        this.audit=audit;
        this.ledger=ledger;
        this.clock=clock;this.gate=gate;this.riskLocks=riskLocks;
        this.tx=new TransactionTemplate(manager);
    }
    public ActionVO previewTransfer(String userId,TransferPreviewRequestDTO r){
        return previewTransferIdentified(userId,null,r);
    }
    public ActionVO previewTransferIdentified(String userId,String serverActionId,TransferPreviewRequestDTO r){
        validation.validate(userId,r.sourceId(),r.recipientId(),r.amountMinor());
        var recipient=recipients.owned(userId,r.recipientId());
        var target=accounts.selectById(recipient.getAccountId());
        if(target==null)throw new BankException("NOT_FOUND","目标账户不存在");
        return prepareIdentified(userId,serverActionId,ActionKind.TRANSFER,new TransferParametersVO(r.sourceId(),r.recipientId(),recipient.getName()+" / "+target.getLabel(),r.amountMinor(),"CNY",0L,null,null));
    }
    public ActionVO previewRevoke(String userId,MandatePreviewRequestDTO r){
        return previewRevokeIdentified(userId,null,r);
    }
    public ActionVO previewRevokeIdentified(String userId,String serverActionId,MandatePreviewRequestDTO r){
        var m=mandates.owned(userId,r.mandateId());
        if(!"ACTIVE".equals(m.getStatus()))throw new BankException("CONFLICT","该代扣授权已撤销");
        return prepareIdentified(userId,serverActionId,ActionKind.REVOKE,new RevokeParametersVO(m.getId(),m.getMerchant(),m.getAmountMinor(),m.getBillingInterval(),"MONTHLY".equals(m.getBillingInterval())?m.getAmountMinor():null,"UNKNOWN","仅撤销银行侧代扣授权，商户合同状态未知"));
    }
    public ActionVO previewSchedule(String userId,SchedulePreviewRequestDTO r){
        return previewScheduleIdentified(userId,null,r);
    }
    public ActionVO previewScheduleIdentified(String userId,String serverActionId,SchedulePreviewRequestDTO r){
        validation.validate(userId,r.sourceId(),r.recipientId(),r.amountMinor());
        LocalDate start,end;
        try{
            start=LocalDate.parse(r.firstDate());
            end=LocalDate.parse(r.endDate());
        }
        catch(Exception e){
            throw new BankException("VALIDATION_ERROR","日期格式应为 YYYY-MM-DD");
        }
        if(r.dayOfMonth()<1||r.dayOfMonth()>31||!ScheduleRules.isDue(start,r.dayOfMonth())||start.isBefore(SimulationClock.businessDate(clock))||end.isBefore(start)||end.isAfter(start.plusYears(1)))throw new BankException("VALIDATION_ERROR","请选择每月 1—31 日；首次日期须为今天或未来并匹配执行日（短月取月末），计划最长一年");
        return prepareIdentified(userId,serverActionId,ActionKind.SCHEDULE,new ScheduleParametersVO(r.sourceId(),r.recipientId(),r.amountMinor(),r.firstDate(),r.endDate(),r.dayOfMonth(),"Asia/Shanghai","本次失败，不补扣"));
    }
    public ActionVO previewMandateActivation(String userId,MandateActivationRequestDTO r){
        LocalDate renewal;
        try{renewal=LocalDate.parse(r.renewalDate());}catch(Exception e){throw new BankException("VALIDATION_ERROR","扣费日期格式错误");}
        if(renewal.isBefore(SimulationClock.businessDate(clock)))throw new BankException("VALIDATION_ERROR","下次扣费日期不能早于今天");
        String merchant=r.merchant().strip();
        if(merchant.isBlank()||r.amountMinor()<=0||r.amountMinor()>1000000||!Set.of("MONTHLY","QUARTERLY","YEARLY").contains(r.billingInterval()))throw new BankException("VALIDATION_ERROR","请完整填写服务、单期金额与扣费周期");
        boolean reactivation=r.mandateId()!=null&&!r.mandateId().isBlank();
        if(reactivation){
            var m=mandates.owned(userId,r.mandateId());
            if(!"REVOKED".equals(m.getStatus())||!merchant.equals(m.getMerchant())||r.amountMinor()!=m.getAmountMinor()||!r.billingInterval().equals(m.getBillingInterval()))throw new BankException("VALIDATION_ERROR","重新开启须使用原服务及金额；新增服务请单独创建");
        }
        if(mandates.list(userId).stream().anyMatch(m->"ACTIVE".equals(m.status())&&merchant.equalsIgnoreCase(m.merchant())))throw new BankException("VALIDATION_ERROR","该服务已开启代扣，请先核对现有授权");
        return prepareIdentified(userId,null,ActionKind.ACTIVATE_MANDATE,new MandateActivationParametersVO(reactivation?r.mandateId():UUID.randomUUID().toString(),merchant,r.amountMinor(),r.billingInterval(),renewal.toString(),reactivation,"开启银行侧周期扣款授权；本次不会立即扣款，商户合同需另行核对"));
    }
    public ActionVO previewScheduleManagement(String userId,String id,ScheduleManageRequestDTO r){
        return riskLocks.withUserLock(userId,()->{
            var s=schedules.selectOwnedForUpdate(userId,id);
            if(s==null)throw new BankException("NOT_FOUND","储蓄计划不存在或不属于当前用户");
            if("CANCELLED".equals(s.getStatus())||!Set.of("EDIT","PAUSE","RESUME","CANCEL").contains(r.operation())||("PAUSE".equals(r.operation())&&!"ACTIVE".equals(s.getStatus()))||("RESUME".equals(r.operation())&&!"PAUSED".equals(s.getStatus())))throw new BankException("VALIDATION_ERROR","计划状态已变化，请刷新后操作");
            boolean edit="EDIT".equals(r.operation());
            String source=edit?r.sourceId():s.getSourceId(),target=edit?r.recipientId():s.getRecipientId();
            Long value=edit?r.amountMinor():s.getAmountMinor();Integer day=edit?r.dayOfMonth():s.getDayOfMonth();
            String first=edit?r.firstDate():s.getFirstDate().toString(),end=edit?r.endDate():s.getEndDate().toString();
            if(source==null||target==null||value==null||day==null||first==null||end==null)throw new BankException("VALIDATION_ERROR","请完整填写新的储蓄规则");
            if(edit){
                validation.validate(userId,source,target,value);
                LocalDate start,finish;try{start=LocalDate.parse(first);finish=LocalDate.parse(end);}catch(Exception e){throw new BankException("VALIDATION_ERROR","日期格式错误");}
                if(day<1||day>31||!ScheduleRules.isDue(start,day)||start.isBefore(SimulationClock.businessDate(clock))||finish.isBefore(start)||finish.isAfter(start.plusYears(1)))throw new BankException("VALIDATION_ERROR","新的首次日期须为今天或未来并匹配每月执行日，计划最长一年");
            }
            if("RESUME".equals(r.operation())&&s.getEndDate().isBefore(SimulationClock.businessDate(clock)))throw new BankException("VALIDATION_ERROR","计划已到期，请先修改有效日期");
            return prepareIdentified(userId,null,ActionKind.MANAGE_SCHEDULE,new ScheduleManagementParametersVO(id,r.operation(),codec.hash(userId,ActionKind.MANAGE_SCHEDULE,ScheduleRules.snapshot(s)),source,target,value,first,end,day,"Asia/Shanghai"));
        });
    }
    ActionVO prepare(String userId,ActionKind kind,ActionParametersVO parameters,boolean delegated){
        return prepareIdentified(userId,null,kind,parameters);
    }
    public ActionVO prepareIdentified(String userId,String serverActionId,ActionKind kind,ActionParametersVO parameters){
        return riskLocks.withUserLock(userId,()->{
            String payload=codec.encode(parameters);String id=serverActionId==null?UUID.randomUUID().toString():serverActionId;
            var existing=actions.selectOwned(userId,id);
            if(existing!=null){if(!existing.getKind().equals(kind.name())||!existing.getPayload().equals(payload))throw new BankException("CONFLICT","原预览参数不匹配");return status(userId,id);}
            var row=new ActionEntity();row.setId(id);row.setUserId(userId);row.setKind(kind.name());row.setPayload(payload);row.setPayloadHash(codec.hash(userId,kind,payload));row.setStatus("AWAITING_AUTH");row.setExpiresAt(OffsetDateTime.now(clock).plusMinutes(5)); if(actions.insert(row)!=1)throw new IllegalStateException("动作写入失败");audit.record(userId,row.getId(),"PREVIEWED");gate.createDecision(userId,row.getId(),cn.safebank.common.RiskTypes.RiskChannel.HTTP);return status(userId,row.getId());
        }
        );
    }
    ActionEntity stored(String userId,String id,boolean lock){
        var row=lock?actions.selectOwnedForUpdate(userId,id):actions.selectOwned(userId,id);
        if(row==null)throw new BankException("NOT_FOUND","资源不存在或不属于当前用户");
        if(!Objects.equals(row.getPayloadHash(),codec.hash(userId,codec.kind(row.getKind()),row.getPayload())))throw new BankException("CONFLICT","操作参数完整性校验失败");
        return row;
    }
    void unexpired(ActionEntity row){
        if(row.getExpiresAt().isBefore(OffsetDateTime.now(clock)))throw new BankException("AUTH_EXPIRED","操作预览/授权已过期，请重新生成");
    }
    void updateState(String userId,String id,ActionStatus state,String receipt,String error){
        if(actions.updateState(userId,id,state.name(),receipt,error)!=1)throw new BankException("CONFLICT","动作更新失败");
    }
    public ActionVO status(String userId,String id){
        var row=stored(userId,id,false);
        return statusView(userId,id,row);
    }
    public List<ActionVO> list(String userId){
        return actions.selectByUser(userId).stream().sorted(Comparator.comparing(ActionEntity::getExpiresAt).reversed()).map(a->statusView(userId,a.getId(),a)).toList();
    }
    /** Within a short transaction, observe current state rather than an RR snapshot. */
    public ActionVO currentStatus(String userId,String id){
        return statusView(userId,id,stored(userId,id,true));
    }
    private ActionVO statusView(String userId,String id,ActionEntity row){
        var kind=codec.kind(row.getKind());
        var parameters=codec.decode(kind,row.getPayload());
        if("VERIFIED_SUCCESS".equals(row.getStatus())&&kind==ActionKind.TRANSFER){
            var p=(TransferParametersVO)parameters;
            String target=recipients.owned(userId,p.recipientId()).getAccountId();
            var entries=ledger.selectByAction(id);
            boolean debit=entries.stream().anyMatch(e->e.getAccountId().equals(p.sourceId())&&e.getAmountMinor()==-p.amountMinor());
            boolean credit=entries.stream().anyMatch(e->e.getAccountId().equals(target)&&e.getAmountMinor()==p.amountMinor());
            if(entries.size()!=2||!debit||!credit)throw new BankException("CONFLICT","账务验证失败，不能报告成功");
        }
        return new ActionVO(row.getId(),row.getUserId(),codec.kind(row.getKind()),row.getPayloadHash(),ActionStatus.valueOf(row.getStatus()),row.getExpiresAt(),row.getReceiptId(),row.getErrorCode(),codec.decode(codec.kind(row.getKind()),row.getPayload()),gate.riskView(userId,id));
    }
    public ActionVO authorize(String userId,String id,AuthorizationRequestDTO request){
        return RiskRejectedException.value(gate.authorize(userId,id,request,cn.safebank.common.RiskTypes.RiskChannel.HTTP));
    }
    public ActionVO cancel(String userId,String id){
        return RiskRejectedException.value(gate.cancel(userId,id,cn.safebank.common.RiskTypes.RiskChannel.HTTP));
    }
}
