package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.ActionVO;
import cn.safebank.dao.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-workflow;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@org.springframework.context.annotation.Import(TestClockConfiguration.class)
class MyBatisWorkflowTest extends BankTestFixture {
    @Autowired cn.safebank.service.WorkflowService workflows;
    @Autowired cn.safebank.service.ActionExecutionService execution;
    @Autowired ActionService actions;
    @Autowired LocalBankExecutionService local;
    @Autowired ScheduleService schedules;
    @Autowired ScheduleMapper scheduleMapper;
    @Autowired ScheduleOccurrenceMapper occurrenceMapper;
    @Autowired PlatformTransactionManager manager;
    @Test void typedWorkflowUsesOnlyVerifiedSavingsAndRejectsOldVersion() {
        var request=new WorkflowRequestDTO("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
        var w=workflows.create("u1",request);
        for(int i=0;i<3;i++){
            var a=(ActionVO)w.pendingAction();bank.authorize("u1",a.id(),a.payloadHash());execution.submit("u1",a.id());w=workflows.resume("u1",w.id(),w.version());
        }
        assertEquals("VERIFIED_SUCCESS",w.state());assertEquals(8300,w.estimatedSavingMinor());assertTrue(w.pendingAction().isEmpty());
        assertEquals(1243000,bank.balance("u1","checking"));assertEquals(1,schedules.list("u1").size());
    }
    @Test void revisionDoesNotPermitOldVersionOrDuplicateSavings() {
        var request=new WorkflowRequestDTO("2026-09",List.of("music"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
        var w=workflows.create("u1",request);var revised=workflows.revise("u1",w.id(),w.version(),request);
        assertEquals(2,revised.version());assertEquals(0,revised.estimatedSavingMinor());
        assertEquals("CONFLICT",assertThrows(BankException.class,()->workflows.resume("u1",w.id(),w.version())).code);
    }
    @Test void concurrentSchedulerInstancesReuseSameOccurrence() throws Exception {
        var a=actions.previewSchedule("u1",new SchedulePreviewRequestDTO("checking","travel-recipient",50000,"2026-11-20","2027-10-20",20));
        bank.authorize("u1",a.id(),a.payloadHash());execution.submit("u1",a.id());
        var second=new ScheduleService(scheduleMapper,occurrenceMapper,actions,context.getBean(cn.safebank.service.ActionExecutionService.class),context.getBean(cn.safebank.service.RiskUserLockService.class),context.getBean(cn.safebank.service.ScheduleAuthorizationService.class),context.getBean(java.time.Clock.class));
        try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<?>>();for(int i=0;i<6;i++){var runner=i%2==0?schedules:second;futures.add(pool.submit(()->runner.runDue("u1","2026-11-20")));}
            for(var f:futures)f.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM occurrences",Integer.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
        assertEquals(1193000,bank.balance("u1","checking"));
    }
}
