package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.ActionStatus;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.entity.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
@Service public class WorkflowService {
    private final ActionService actions;
    private final ActionExecutionService execution;
    private final StatementService statements;
    private final AccountService accounts;
    private final RecipientService recipients;
    private final MandateService mandates;
    private final WorkflowMapper workflows;
    private final WorkflowStepMapper stepRows;
    private final ExecutionObservationMapper observations;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;private final RiskUserLockService riskLocks;
    private record Stored(UUID id,String userId,long version,String state,WorkflowRequestDTO request,StatementVO analysis) {
    }
    public WorkflowService(ActionService actions,ActionExecutionService execution,StatementService statements,AccountService accounts,RecipientService recipients,MandateService mandates,WorkflowMapper workflows,WorkflowStepMapper stepRows,ExecutionObservationMapper observations,PlatformTransactionManager manager,ObjectMapper json,Clock clock,RiskUserLockService riskLocks){
        this.actions=actions;
        this.execution=execution;
        this.statements=statements;
        this.accounts=accounts;
        this.recipients=recipients;
        this.mandates=mandates;
        this.workflows=workflows;
        this.stepRows=stepRows;
        this.observations=observations;
        this.tx=new TransactionTemplate(manager);
        this.json=json;
        this.clock=clock;this.riskLocks=riskLocks;
    }
    private BankException invalid(String text){
        return new BankException("VALIDATION_ERROR",text);
    }
    private String encode(Object value){
        try{
            return json.writeValueAsString(value);
        }
        catch(Exception e){
            throw new BankException("CONFLICT","任务记录无法编码");
        }
    }
    private Stored load(String user,UUID id,boolean lock){
        var row=lock?workflows.selectOwnedForUpdate(user,id.toString()):workflows.selectOwned(user,id.toString());
        if(row==null)throw new BankException("NOT_FOUND","任务不存在或不属于当前用户");
        try{
            return new Stored(id,user,row.getVersion(),row.getState(),json.readValue(row.getRequestJson(),WorkflowRequestDTO.class),json.readValue(row.getAnalysisJson(),StatementVO.class));
        }
        catch(Exception e){
            throw new BankException("CONFLICT","任务记录无法读取");
        }
    }
    private void version(Stored w,long expected){
        if(w.version()!=expected)throw new BankException("CONFLICT","任务版本已改变，请刷新后再操作");
    }
    private void validate(String user,WorkflowRequestDTO r,UUID existing){
        if(r==null||!r.onlyConfirmedSavings()||r.mandateIds().isEmpty()||r.mandateIds().size()>10||new HashSet<>(r.mandateIds()).size()!=r.mandateIds().size())throw invalid("请选择不重复的代扣，并只使用本任务已核验撤销的预计节省额");
        statements.statement(user,r.month());
        accounts.balance(user,r.sourceId());
        if(recipients.search(user,r.recipientId()).size()!=1)throw invalid("需要明确的本人收款人标识");
        String account=recipients.owned(user,r.recipientId()).getAccountId();
        if(r.sourceId().equals(account))throw invalid("储蓄目标不能与付款账户相同");
        LocalDate first,end;
        try{
            first=LocalDate.parse(r.firstDate());
            end=LocalDate.parse(r.endDate());
        }
        catch(Exception e){
            throw invalid("请填写合法的首次和结束日期");
        }
        if(r.dayOfMonth()<1||r.dayOfMonth()>31||!cn.safebank.common.ScheduleRules.isDue(first,r.dayOfMonth())||end.isBefore(first)||end.isAfter(first.plusYears(1))||first.isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw invalid("首次日期须为今天或未来、匹配每月1—31日（短月取月末），计划最长一年");
        for(String id:r.mandateIds()){
            var m=mandates.owned(user,id);
            if(!"MONTHLY".equals(m.getBillingInterval()))throw invalid("仅支持已知月度扣费；不能把未知周期推算为月度节省");
            boolean done=existing!=null&&steps(existing).stream().anyMatch(s->s.key().equals("revoke:"+id)&&s.status().equals("VERIFIED_SUCCESS"));
            if(!"ACTIVE".equals(m.getStatus())&&!done)throw new BankException("CONFLICT","选中的代扣已撤销，不能计为本任务新增节省");
        }
    }
    public WorkflowVO create(String user,WorkflowRequestDTO request){
        return createIdentified(user,UUID.randomUUID(),request);
    }
    public WorkflowVO createIdentified(String user,UUID id,WorkflowRequestDTO request){
        validate(user,request,null);
        return riskLocks.withUserLock(user,()->{
            var old=workflows.selectOwned(user,id.toString());if(old!=null){if(!old.getRequestJson().equals(encode(request)))throw new BankException("CONFLICT","原联合任务参数不匹配");return get(user,id);}
            var row=new WorkflowEntity();row.setId(id.toString());row.setUserId(user);row.setVersion(1L);row.setState("PLANNING");row.setRequestJson(encode(request));row.setAnalysisJson(encode(statements.statement(user,request.month())));row.setCreatedAt(OffsetDateTime.now(clock));require(workflows.insert(row));advance(user,load(user,id,true));return get(user,id);
        }
        );
    }
    public List<WorkflowVO> list(String user){
        return workflows.selectByUser(user).stream().map(r->get(user,UUID.fromString(r.getId()))).toList();
    }
    private List<WorkflowStepVO> steps(UUID id){
        return stepRows.selectByWorkflow(id.toString()).stream().map(r->new WorkflowStepVO(r.getStepKey(),r.getKind(),r.getStatus(),r.getActionId(),r.getInputVersion(),r.getSavingMinor(),r.getErrorCode())).toList();
    }
    private boolean terminal(String state){
        return List.of("VERIFIED_SUCCESS","VERIFIED_FAILED","CANCELLED").contains(state);
    }
    private long saving(Stored w,List<WorkflowStepVO> all){
        return all.stream().filter(s->s.kind().equals("REVOKE")&&s.status().equals("VERIFIED_SUCCESS")&&w.request().mandateIds().contains(s.key().substring(7))).mapToLong(WorkflowStepVO::savingMinor).sum();
    }
    public WorkflowVO get(String user,UUID id){
        var w=load(user,id,false);
        var all=steps(id);
        PendingActionVO pending=all.stream().filter(s->!terminal(s.status())).findFirst().<PendingActionVO>map(s->snapshot(user,s)).orElse(NoPendingActionVO.INSTANCE);
        boolean partial=all.stream().anyMatch(s->s.status().equals("VERIFIED_SUCCESS"))&&!w.state().equals("VERIFIED_SUCCESS");
        return new WorkflowVO(id,w.version(),w.state(),w.request(),saving(w,all),partial,all,pending,w.analysis());
    }
    private ActionVO snapshot(String user,WorkflowStepVO step){
        var action=actions.status(user,step.actionId());
        boolean stale=List.of("AWAITING_AUTH","AUTHORIZED").contains(step.status())&&!List.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED).contains(action.status());
        return action.withObservation(stale?ActionStatus.UNKNOWN:ActionStatus.valueOf(step.status()),action.receiptId(),stale?"WORKFLOW_SYNC_REQUIRED":step.errorCode());
    }
    public synchronized WorkflowVO resume(String user,UUID id,long expectedVersion){
        var w=load(user,id,false);
        version(w,expectedVersion);
        if(w.state().equals("CANCELLED")||w.state().equals("VERIFIED_SUCCESS"))return get(user,id);
        for(var step:steps(id))if(List.of("AWAITING_AUTH","AUTHORIZED").contains(step.status())){
            var action=actions.status(user,step.actionId());
            if(observations.selectOwned(user,step.actionId())==null&&action.expiresAt().isBefore(OffsetDateTime.now(clock)))return get(user,id);
        }
        for(var step:steps(id))if(!terminal(step.status())){
            var observed=execution.observe(user,step.actionId());
            riskLocks.withUserLock(user,()->{
                version(load(user,id,true),expectedVersion);require(stepRows.updateObserved(id.toString(),step.actionId(),observed.action().status().name(),observed.action().errorCode(),encode(observed)));return null;
            }
            );
        }
        return riskLocks.withUserLock(user,()->{
            var current=load(user,id,true);version(current,expectedVersion);if(current.state().equals("CANCEL_REQUESTED")){
                cancelAvailable(user,current);state(user,id,steps(id).stream().allMatch(step->terminal(step.status()))?"CANCELLED":"CANCEL_REQUESTED");
            }
            else advance(user,current);return get(user,id);
        }
        );
    }
    private void require(int count){
        if(count!=1)throw new BankException("CONFLICT","任务记录更新失败");
    }
    private void state(String user,UUID id,String state){
        require(workflows.updateState(user,id.toString(),state,OffsetDateTime.now(clock)));
    }
    private void insert(Stored w,String key,ActionVO action,long amount){
        var row=new WorkflowStepEntity();
        row.setWorkflowId(w.id().toString());
        row.setStepKey(key);
        row.setInputVersion(w.version());
        row.setKind(action.kind().name());
        row.setStatus("AWAITING_AUTH");
        row.setActionId(action.id());
        row.setSavingMinor(amount);
        require(stepRows.insert(row));
        state(w.userId(),w.id(),"AWAITING_AUTH");
    }
    private void advance(String user,Stored w){
        var all=steps(w.id());
        var open=all.stream().filter(s->!terminal(s.status())).findFirst();
        if(open.isPresent()){
            String status=open.get().status();
            state(user,w.id(),List.of("UNKNOWN","PENDING").contains(status)?status:"AWAITING_AUTH");
            return;
        }
        for(String mandate:w.request().mandateIds()){
            String key="revoke:"+mandate;
            if(all.stream().anyMatch(s->s.key().equals(key)&&(!s.status().equals("CANCELLED")||s.inputVersion()==w.version())))continue;
            var preview=actions.previewRevoke(user,new MandatePreviewRequestDTO(mandate));
            insert(w,key,preview,((RevokeParametersVO)preview.parameters()).estimatedMonthlySavingMinor());
            return;
        }
        long amount=saving(w,all);
        if(amount==0){
            state(user,w.id(),"VERIFIED_FAILED");
            return;
        }
        var schedule=all.stream().filter(s->s.kind().equals("SCHEDULE")&&s.inputVersion()==w.version()).findFirst();
        if(schedule.isPresent()){
            boolean failed=all.stream().anyMatch(s->s.status().equals("VERIFIED_FAILED"));
            state(user,w.id(),schedule.get().status().equals("VERIFIED_SUCCESS")?(failed?"PARTIAL_SUCCESS":"VERIFIED_SUCCESS"):"PARTIAL_SUCCESS");
            return;
        }
        var r=w.request();
        insert(w,"schedule",actions.previewSchedule(user,new SchedulePreviewRequestDTO(r.sourceId(),r.recipientId(),amount,r.firstDate(),r.endDate(),r.dayOfMonth())),0);
    }
    private void cancelOpen(String user,Stored w){
        for(var step:steps(w.id()).stream().sorted(java.util.Comparator.comparing(WorkflowStepVO::actionId)).toList())if(!terminal(step.status())){
            if(observations.selectOwned(user,step.actionId())!=null||List.of("UNKNOWN","PENDING").contains(step.status()))throw new BankException("CONFLICT","已受理或状态未知的动作需要先查单；不能取消或改参");
            actions.cancel(user,step.actionId());
            require(stepRows.cancel(w.id().toString(),step.actionId()));
        }
    }
    public synchronized WorkflowVO revise(String user,UUID id,long expectedVersion,WorkflowRequestDTO request){
        var w=load(user,id,false);
        version(w,expectedVersion);
        validate(user,request,id);
        return riskLocks.withUserLock(user,()->{
            var current=load(user,id,true);version(current,expectedVersion);if(List.of("VERIFIED_SUCCESS","PARTIAL_SUCCESS","CANCELLED","CANCEL_REQUESTED").contains(current.state()))throw new BankException("CONFLICT","已完成或已申请取消的任务不能改参，请新建任务");cancelOpen(user,current);require(workflows.revise(user,id.toString(),expectedVersion,encode(request),encode(statements.statement(user,request.month()))));advance(user,load(user,id,true));return get(user,id);
        }
        );
    }
    public synchronized WorkflowVO cancel(String user,UUID id,long expectedVersion){
        return riskLocks.withUserLock(user,()->{
            var w=load(user,id,true);version(w,expectedVersion);if(List.of("VERIFIED_SUCCESS","PARTIAL_SUCCESS").contains(w.state()))throw new BankException("CONFLICT","已完成的银行动作不能通过取消任务撤回");cancelAvailable(user,w);state(user,id,steps(id).stream().allMatch(step->terminal(step.status()))?"CANCELLED":"CANCEL_REQUESTED");return get(user,id);
        }
        );
    }
    private void cancelAvailable(String user,Stored w){
        for(var step:steps(w.id()).stream().sorted(java.util.Comparator.comparing(WorkflowStepVO::actionId)).toList())if(!terminal(step.status())){
            if(observations.selectOwned(user,step.actionId())==null&&List.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED).contains(actions.status(user,step.actionId()).status())){
                actions.cancel(user,step.actionId());
                require(stepRows.cancel(w.id().toString(),step.actionId()));
            }
        }
    }
}
