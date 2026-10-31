package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskSchedule;DB_CLOSE_DELAY=-1"})
@Import(RiskConcurrencyTest.Clocks.class)
class RiskScheduleTest extends BankTestFixture {
    @Autowired ScheduleService schedules;@Autowired MutableRiskClock clock;@Autowired RiskUserLockService locks;@Autowired WorkflowService workflows;
    @Autowired VerificationService verifier;
    @BeforeEach void time(){clock.set(Instant.parse("2026-10-09T04:00:00Z"));}
    private String plan(long amount){String id=authorize(bank.previewSchedule("u1","checking","zhang-a",amount,"2026-11-09","2027-10-09",9));bank.submit("u1",id);return bank.status("u1",id).get("receipt_id").toString();}
    @Test void redOccurrencePausesAndReusesOriginalAction(){String sid=plan(150000);var first=schedules.runDue("u1","2026-11-09").getFirst();var repeat=schedules.runDue("u1","2026-11-09").getFirst();assertEquals(first.id(),repeat.id());assertEquals("AWAITING_AUTH",first.status().name());assertEquals("RED",first.security().riskLevel().name());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM occurrences WHERE schedule_id=?",Integer.class,sid));}
    @Test void missingScopeDoesNotDelegate(){String sid=plan(50000);db.update("DELETE FROM schedule_authorizations WHERE schedule_id=?",sid);var result=schedules.runDue("u1","2026-11-09").getFirst();assertEquals("AWAITING_AUTH",result.status().name());assertEquals(1243000,bank.balance("u1","checking"));}
    @Test void redOccurrenceCanBeVerifiedAfterReturningToPage(){plan(150000);var a=schedules.runDue("u1","2026-11-09").getFirst();bank.authorize("u1",a.id(),a.payloadHash());bank.submit("u1",a.id());assertEquals(LocalDate.of(2026,11,9),db.queryForObject("SELECT business_date FROM risk_transfer_usage WHERE action_id=?",LocalDate.class,a.id()));assertEquals(LocalDate.of(2026,11,9),db.queryForObject("SELECT booked_on FROM transactions WHERE id=?",LocalDate.class,a.id()+":debit"));assertEquals(1093000,bank.balance("u1","checking"));}
    @Test void futurePeriodDoesNotExtendGlobalChallengeCooldown(){
        plan(150000);var a=schedules.runDue("u1","2026-11-09").getFirst();var first=verifier.create("u1",a.id(),new ChallengeRequestDTO(a.payloadHash(),a.security().decisionId()),cn.safebank.common.RiskTypes.RiskChannel.HTTP);assertTrue(first.accepted());
        assertEquals(clock.instant(),db.queryForObject("SELECT last_challenge_at FROM risk_user_guards WHERE user_id='u1'",OffsetDateTime.class).toInstant());
        clock.set(clock.instant().plusSeconds(61));String id=bank.preview("u1","checking","zhang-a",150000).get("id").toString();var b=context.getBean(ActionService.class).status("u1",id);
        assertTrue(verifier.create("u1",id,new ChallengeRequestDTO(b.payloadHash(),b.security().decisionId()),cn.safebank.common.RiskTypes.RiskChannel.HTTP).accepted());
    }
    @Test void simulatedDateControlsRiskAndBookingTogether(){plan(50000);var result=schedules.runDue("u1","2026-11-09").getFirst();assertEquals("VERIFIED_SUCCESS",result.status().name());assertEquals(LocalDate.of(2026,11,9),db.queryForObject("SELECT business_date FROM risk_transfer_usage WHERE action_id=?",LocalDate.class,result.id()));assertEquals(LocalDate.of(2026,11,9),db.queryForObject("SELECT booked_on FROM transactions WHERE id=?",LocalDate.class,result.id()+":debit"));assertEquals(LocalDate.of(2026,10,9),LocalDate.now(clock));}
    @Test void workflowRevisionDoesNotReverseLockOrder() throws Exception {
        var request=new WorkflowRequestDTO("2026-09",java.util.List.of("music"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
        var w=workflows.create("u1",request);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->locks.withUserLock("u1",()->{started.countDown();return workflows.get("u1",w.id());}));
            assertTrue(started.await(5,TimeUnit.SECONDS));var b=pool.submit(()->workflows.revise("u1",w.id(),1,request));a.get(5,TimeUnit.SECONDS);assertEquals(2,b.get(5,TimeUnit.SECONDS).version());
        }
    }
}
