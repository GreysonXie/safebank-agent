package cn.safebank;

import cn.safebank.common.ActionStatus;
import cn.safebank.dao.*;
import cn.safebank.dto.SchedulePreviewRequestDTO;
import cn.safebank.service.*;
import cn.safebank.vo.ActionVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-regressions;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@org.springframework.context.annotation.Import(TestClockConfiguration.class)
class MyBatisMigrationRegressionTest extends BankTestFixture {
    @Autowired StatementService statements;
    @Autowired ActionService actions;
    @Autowired LocalBankExecutionService execution;
    @Autowired ScheduleMapper scheduleMapper;
    @Autowired ScheduleOccurrenceMapper occurrenceMapper;
    @Autowired ScheduleService schedules;
    @Autowired PlatformTransactionManager manager;

    @Test void nullableLegacyCategoryStillHasAnEmptyCategoryBucket() {
        db.update("UPDATE transactions SET category=NULL WHERE id='rent-9'");
        var statement=statements.statement("u1","2026-09");
        assertEquals(340300,statement.expenseMinor());
        assertEquals(250000,statement.categories().get(""));
        assertNull(statement.transactions().getFirst().category());
        assertEquals(-250000,statement.anomalyCandidates().getFirst().amountMinor());
    }

    @Test void cancelledLegacyScheduleKeepsNullableValuesAndIsNeverRun() {
        db.update("INSERT INTO schedules(id,user_id,amount_minor,day_of_month,status) VALUES('legacy-cancelled','u1',NULL,NULL,'CANCELLED')");
        var row=schedules.list("u1").getFirst();
        assertNull(row.amountMinor());assertNull(row.dayOfMonth());assertNull(row.firstDate());
        assertTrue(schedules.runDue("u1","2026-11-20").isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }

    @Test void concurrentFailedOccurrencesReturnSameFailureInsteadOfConflict() throws Exception {
        var schedule=actions.previewSchedule("u1",new SchedulePreviewRequestDTO("checking","travel-recipient",50000,"2026-11-20","2027-10-20",20));
        bank.authorize("u1",schedule.id(),schedule.payloadHash());bank.submit("u1",schedule.id());
        db.update("UPDATE accounts SET balance_minor=0 WHERE id='checking'");
        var coordinatedReads=spy(actions);var bothReadAuthorized=new CyclicBarrier(2);
        doAnswer(invocation->{
            ActionVO current=(ActionVO)invocation.callRealMethod();
            // prepare() also reads status inside the schedule lock, before occurrence insertion.
            // Synchronize only the post-rollback failure reads outside that transaction.
            if(current.status()==ActionStatus.AUTHORIZED&&db.queryForObject("SELECT COUNT(*) FROM occurrences",Integer.class)>0)
                bothReadAuthorized.await(10,TimeUnit.SECONDS);
            return current;
        }).when(coordinatedReads).status(eq("u1"),anyString());
        var first=new ScheduleService(scheduleMapper,occurrenceMapper,coordinatedReads,context.getBean(cn.safebank.service.ActionExecutionService.class),context.getBean(cn.safebank.service.RiskUserLockService.class),context.getBean(cn.safebank.service.ScheduleAuthorizationService.class),context.getBean(java.time.Clock.class));
        var second=new ScheduleService(scheduleMapper,occurrenceMapper,coordinatedReads,context.getBean(cn.safebank.service.ActionExecutionService.class),context.getBean(cn.safebank.service.RiskUserLockService.class),context.getBean(cn.safebank.service.ScheduleAuthorizationService.class),context.getBean(java.time.Clock.class));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->first.runDue("u1","2026-11-20").getFirst());
            var b=pool.submit(()->second.runDue("u1","2026-11-20").getFirst());
            var one=a.get(15,TimeUnit.SECONDS);var two=b.get(15,TimeUnit.SECONDS);
            assertEquals(one.id(),two.id());assertEquals(ActionStatus.VERIFIED_FAILED,one.status());
            assertEquals(ActionStatus.VERIFIED_FAILED,two.status());assertEquals("INSUFFICIENT_FUNDS",two.errorCode());
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM occurrences",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit WHERE event='BANK_REJECTED'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class));
    }
}
