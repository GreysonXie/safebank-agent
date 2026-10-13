package cn.safebank;
import cn.safebank.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskConcurrency;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@Import(RiskConcurrencyTest.Clocks.class)
class RiskConcurrencyTest extends BankTestFixture {
    @TestConfiguration static class Clocks { @Bean @Primary MutableRiskClock riskClock(){return new MutableRiskClock(Instant.parse("2026-10-09T15:59:59Z"));} }
    @Autowired RiskUserLockService locks;
    @Autowired DailyUsageService usage;
    @Autowired MutableRiskClock clock;
    private String legacy(String day,String state,long amount){
        String id=bank.preview("u1","checking","zhang-a",amount).get("id").toString();
        locks.withUserLock("u1",()->null);
        db.update("INSERT INTO risk_transfer_usage(action_id,user_id,provenance,business_date,amount_minor,state,reserved_until,updated_at) VALUES(?,'u1','LEGACY_OBSERVED',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,day==null?null:LocalDate.parse(day),amount,state);
        return id;
    }
    @Test void unknownFromPreviousDayStillCounts(){
        legacy("2026-10-08","SUBMITTED",60000);
        legacy("2026-10-08","COMMITTED",20000);
        assertEquals(60000,usage.effectiveMinor("u1",LocalDate.of(2026,10,9),clock.instant(),null));
    }
    @Test void ownReservationIsNotDoubleCounted(){
        String id=legacy("2026-10-09","COMMITTED",60000);
        assertEquals(0,usage.effectiveMinor("u1",LocalDate.of(2026,10,9),clock.instant(),id));
    }
    @Test void midnightQueuedAuthorizationUsesLockedDate() throws Exception {
        clock.set(Instant.parse("2026-10-09T15:59:59Z"));
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),queued=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var owner=pool.submit(()->locks.withUserLock("u1",()->{held.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            var waiter=pool.submit(()->{queued.countDown();return locks.withUserLock("u1",()->LocalDate.now(clock));});
            assertTrue(queued.await(5,TimeUnit.SECONDS));
            clock.set(Instant.parse("2026-10-09T16:00:01Z"));release.countDown();
            assertTrue(owner.get(5,TimeUnit.SECONDS));
            assertEquals(LocalDate.of(2026,10,10),waiter.get(5,TimeUnit.SECONDS));
        }
    }
}
