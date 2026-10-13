package cn.safebank;
import static cn.safebank.WorkflowTestContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:recovery;DB_CLOSE_DELAY=-1"})
@org.springframework.context.annotation.Import(TestClockConfiguration.class)
class WorkflowRecoveryTest extends BankTestFixture {
    @Autowired WorkflowTestSupport workflows;@Autowired ExecutionTestSupport execution;@Autowired PlatformTransactionManager manager;@Autowired ObjectMapper json;@Autowired Clock clock;@Autowired cn.safebank.bank.BankAdapter adapter;
    @Autowired cn.safebank.service.ActionService actions;
    @Autowired cn.safebank.service.LocalBankExecutionService localExecution;
    @Autowired cn.safebank.dao.ExecutionObservationMapper observations;
    @Autowired org.springframework.context.ApplicationContext context;
    WorkflowTestSupport fresh() {
        var freshExecution=new cn.safebank.service.ActionExecutionService(actions,localExecution,adapter,observations,context.getBean(cn.safebank.service.AuthorizationGateService.class),context.getBean(cn.safebank.service.RiskUserLockService.class),context.getBean(cn.safebank.service.RiskQueryService.class),clock);
        var freshWorkflow=new cn.safebank.service.WorkflowService(actions,freshExecution,
            context.getBean(cn.safebank.service.StatementService.class),context.getBean(cn.safebank.service.AccountService.class),
            context.getBean(cn.safebank.service.RecipientService.class),context.getBean(cn.safebank.service.MandateService.class),
            context.getBean(cn.safebank.dao.WorkflowMapper.class),context.getBean(cn.safebank.dao.WorkflowStepMapper.class),observations,manager,json,clock,context.getBean(cn.safebank.service.RiskUserLockService.class));
        return new WorkflowTestSupport(freshWorkflow,json);
    }
    @Test void freshServiceRestoresPreviewAndPostCommitProgressWithoutRepeatingAction() {
        var r=new WorkflowRequest("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
        var w=workflows.create("u1",r);String id=w.pendingAction().get("id").toString();
        assertEquals(id,fresh().resume("u1",w.id(),1).pendingAction().get("id"));
        execution.submit("u1",authorize(w.pendingAction())); // process ends before workflow reconciliation
        var recovered=fresh().resume("u1",w.id(),1);assertEquals(1500,recovered.estimatedSavingMinor());assertEquals(2,recovered.steps().size());
        assertNotEquals(id,recovered.pendingAction().get("id"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit WHERE action_id=? AND event='EXECUTED'",Integer.class,id));assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void restartBetweenIntentAndBankAcceptanceRestoresSameActionForExplicitRetry() {
        var r=new WorkflowRequest("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
        var w=workflows.create("u1",r);String id=authorize(w.pendingAction());
        db.update("INSERT INTO execution_observations VALUES(?,?,'UNKNOWN',NULL,'SUBMISSION_STARTED',?)",id,"u1",java.time.OffsetDateTime.now(clock));
        var restarted=fresh().resume("u1",w.id(),1);assertEquals("AWAITING_AUTH",restarted.pendingAction().get("status"));assertEquals(id,restarted.pendingAction().get("id"));assertEquals(0,restarted.estimatedSavingMinor());
        bank.authorize("u1",id,actions.status("u1",id).payloadHash());execution.submit("u1",id);assertEquals(1500,fresh().resume("u1",w.id(),1).estimatedSavingMinor());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit WHERE action_id=? AND event='EXECUTED'",Integer.class,id));
    }
}
