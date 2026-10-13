package cn.safebank;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
@org.springframework.context.annotation.Import(TestVerificationConfiguration.class)
@TestConfiguration
public class TestBankConfiguration {
    @Bean BankTestSupport bankTestSupport(AccountService accounts,RecipientService recipients,MandateService mandates,StatementService statements,ActionService actions,LocalBankExecutionService local,ScheduleService schedules,AuditService audit,ObjectMapper json,VerificationService verifier,CapturingVerificationProvider provider,ActionExecutionService coordination,AuthorizationGateService gate) { return new BankTestSupport(accounts,recipients,mandates,statements,actions,local,schedules,audit,json,verifier,provider,coordination,gate); }
    @Bean WorkflowTestSupport workflowTestSupport(WorkflowService workflows,ObjectMapper json) { return new WorkflowTestSupport(workflows,json); }
    @Bean ExecutionTestSupport executionTestSupport(ActionExecutionService execution,ObjectMapper json) { return new ExecutionTestSupport(execution,json); }
}
