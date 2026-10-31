package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.bank.*;
import cn.safebank.common.RiskTypes.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskExecution;DB_CLOSE_DELAY=-1","bank.simulation.faults-enabled=true"})
@Import(RiskConcurrencyTest.Clocks.class)
class RiskExecutionTest extends BankTestFixture {
    @Autowired AuthorizationGateService gate;@Autowired ActionExecutionService execution;@Autowired LocalBankExecutionService local;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean FaultInjectingBankAdapter faults;@Autowired MutableRiskClock clock;
    @BeforeEach void time(){clock.set(Instant.parse("2026-10-09T04:00:00Z"));}
    String action(){return authorize(bank.preview("u1","checking","zhang-a",50000));}
    @Test void submissionStateIsAtomicBeforeProviderCall(){String id=action();assertTrue(gate.beginSubmission("u1",id,RiskChannel.HTTP).accepted());assertEquals("SUBMITTED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,id));assertEquals("SUBMISSION_STARTED",db.queryForObject("SELECT status FROM action_authorizations WHERE action_id=?",String.class,id));assertEquals("SUBMISSION_STARTED",db.queryForObject("SELECT error_code FROM execution_observations WHERE action_id=?",String.class,id));assertEquals(1243000,bank.balance("u1","checking"));}
    @Test void crashAfterSubmissionMarkerOnlyLooksUpOriginalAction(){String id=action();gate.beginSubmission("u1",id,RiskChannel.HTTP);var result=execution.submit("u1",id);assertEquals(id,result.action().id());assertEquals(1243000,bank.balance("u1","checking"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));}
    @Test void unknownIsNotReleasedByExpiry(){String id=action();gate.beginSubmission("u1",id,RiskChannel.HTTP);faults.configure("u1",id,NONE,LOOKUP_UNAVAILABLE);clock.set(clock.instant().plusSeconds(86400));execution.observe("u1",id);assertEquals("SUBMITTED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,id));assertEquals(1243000,bank.balance("u1","checking"));}
    @Test void acceptedPendingCanFinishAfterGrantExpiry(){String id=action();faults.configure("u1",id,PENDING_THEN_SUCCESS,NONE);execution.submit("u1",id);clock.set(clock.instant().plusSeconds(600));assertEquals(cn.safebank.common.ActionStatus.VERIFIED_SUCCESS,execution.observe("u1",id).action().status());assertEquals("COMMITTED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,id));assertEquals(1193000,bank.balance("u1","checking"));}
    @Test void notSubmittedReturnsSameActionForReauthorization(){String id=action();gate.beginSubmission("u1",id,RiskChannel.HTTP);var result=execution.observe("u1",id);assertEquals(id,result.action().id());assertEquals(cn.safebank.common.ActionStatus.AWAITING_AUTH,result.action().status());assertEquals("RELEASED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,id));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM execution_observations",Integer.class));}
    @Test void directLocalBankCallCannotBypassGate(){String id=action();assertEquals("AUTH_REQUIRED",assertThrows(BankException.class,()->local.submit("u1",id)).code);assertEquals(1243000,bank.balance("u1","checking"));}
    @Test void lookupCannotClearMarkerWhileSubmissionIsInFlight() throws Exception {
        String id=action();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var lookedUp=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return inv.callRealMethod();}).when(faults).submit("u1",id);
        org.mockito.Mockito.doAnswer(inv->{lookedUp.countDown();return inv.callRealMethod();}).when(faults).lookup("u1",id);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->execution.submit("u1",id));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));var two=pool.submit(()->execution.observe("u1",id));
            boolean premature=lookedUp.await(300,java.util.concurrent.TimeUnit.MILLISECONDS);release.countDown();
            assertFalse(premature,"lookup reached bank before initial submission completed");assertEquals(cn.safebank.common.ActionStatus.VERIFIED_SUCCESS,one.get(5,java.util.concurrent.TimeUnit.SECONDS).action().status());assertEquals(cn.safebank.common.ActionStatus.VERIFIED_SUCCESS,two.get(5,java.util.concurrent.TimeUnit.SECONDS).action().status());
        }finally{release.countDown();}
        assertEquals(1193000,bank.balance("u1","checking"));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,id));
    }
}
