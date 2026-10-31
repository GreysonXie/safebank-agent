package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
public class BankTestSupport {
    private final AccountService accounts;private final RecipientService recipients;private final MandateService mandates;private final StatementService statements;
    private final ActionExecutionService coordination;private final AuthorizationGateService gate;private final VerificationService verifier;private final CapturingVerificationProvider provider;
    private final ActionService actions;private final LocalBankExecutionService execution;private final ScheduleService schedules;private final AuditService audit;private final ObjectMapper compatibility;
    public BankTestSupport(AccountService accounts,RecipientService recipients,MandateService mandates,StatementService statements,ActionService actions,LocalBankExecutionService execution,ScheduleService schedules,AuditService audit,ObjectMapper compatibility,VerificationService verifier,CapturingVerificationProvider provider,ActionExecutionService coordination,AuthorizationGateService gate){this.coordination=coordination;this.gate=gate;this.verifier=verifier;this.provider=provider;this.accounts=accounts;this.recipients=recipients;this.mandates=mandates;this.statements=statements;this.actions=actions;this.execution=execution;this.schedules=schedules;this.audit=audit;this.compatibility=compatibility;}
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object value){return (Map<String,Object>)compatibility.convertValue(value,Object.class);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)compatibility.convertValue(value,Object.class);}
    public List<Map<String,Object>> accounts(String user){return list(accounts.list(user));}
    public long balance(String user,String id){return accounts.balance(user,id);}
    public List<Map<String,Object>> recipients(String user,String query){return list(recipients.search(user,query));}
    public List<Map<String,Object>> mandates(String user){return list(mandates.list(user));}
    public Map<String,Object> statement(String user,String month){return object(statements.statement(user,month));}
    public List<Map<String,Object>> recurring(String user){return list(statements.recurring(user));}
    public List<Map<String,Object>> schedules(String user){return list(schedules.list(user));}
    public List<Map<String,Object>> events(String user){return list(audit.list(user));}
    public Map<String,Object> preview(String user,String source,String target,long amount){return object(actions.previewTransfer(user,new TransferPreviewRequestDTO(source,target,amount)));}
    public Map<String,Object> previewRevoke(String user,String mandate){return object(actions.previewRevoke(user,new MandatePreviewRequestDTO(mandate)));}
    public Map<String,Object> previewSchedule(String user,String source,String target,long amount,String first,String end,int day){return object(actions.previewSchedule(user,new SchedulePreviewRequestDTO(source,target,amount,first,end,day)));}
    public Map<String,Object> authorize(String user,String id,String hash){var a=actions.status(user,id);String proof=null;
        if(a.security().riskLevel()==cn.safebank.common.RiskTypes.RiskLevel.RED){
            var c=cn.safebank.common.RiskRejectedException.value(verifier.create(user,id,new ChallengeRequestDTO(hash,a.security().decisionId()),cn.safebank.common.RiskTypes.RiskChannel.HTTP));
            proof=cn.safebank.common.RiskRejectedException.value(verifier.verify(user,id,c.challengeId(),new ChallengeVerifyRequestDTO(provider.code(c.challengeId())))).verificationProofId();
        }
        return object(actions.authorize(user,id,new AuthorizationRequestDTO(hash,a.security().decisionId(),true,proof)));}
    public Map<String,Object> cancel(String user,String id){return object(actions.cancel(user,id));}
    public Map<String,Object> status(String user,String id){return object(actions.status(user,id));}
    public Map<String,Object> submit(String user,String id){return object(coordination.submit(user,id));}
    public Map<String,Object> acceptPending(String user,String id){cn.safebank.common.RiskRejectedException.value(gate.beginSubmission(user,id,cn.safebank.common.RiskTypes.RiskChannel.HTTP));return object(execution.acceptPending(user,id));}
    public Map<String,Object> rejectAuthorized(String user,String id,String code){cn.safebank.common.RiskRejectedException.value(gate.beginSubmission(user,id,cn.safebank.common.RiskTypes.RiskChannel.HTTP));return object(execution.rejectAuthorized(user,id,code));}
    public Map<String,Object> rejectUnaccepted(String user,String id,String code){cn.safebank.common.RiskRejectedException.value(gate.beginSubmission(user,id,cn.safebank.common.RiskTypes.RiskChannel.HTTP));return object(execution.rejectUnaccepted(user,id,code));}
    public Map<String,Object> completePending(String user,String id){return object(execution.completePending(user,id));}
    public List<Map<String,Object>> runDue(String user,String date){return list(schedules.runDue(user,date));}
}
