package cn.safebank;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:banktest;DB_CLOSE_DELAY=-1"})
@org.springframework.context.annotation.Import(TestBankConfiguration.class)
class BankServiceTest {
    @Autowired BankTestSupport bank;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource datasource;
    @BeforeEach void reset() {
        jdbc.execute("DROP ALL OBJECTS");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("schema-risk.sql"),new ClassPathResource("data.sql")).execute(datasource);
    }
    @Test void authorizedTransferDebitsOnceAndHasVerifiedReceipt() {
        var a=bank.preview("u1","checking","zhang-a",50000);
        String id=a.get("id").toString();
        bank.authorize("u1",id,a.get("payload_hash").toString());
        assertEquals("VERIFIED_SUCCESS",bank.submit("u1",id).get("status"));
        bank.submit("u1",id);
        assertEquals(1193000,bank.balance("u1","checking"));
        assertEquals(50000L,((Number)bank.status("u1",id).get("amount_minor")).longValue());
    }
    @Test void unauthorizedTransferDoesNotMoveMoney() {
        var a=bank.preview("u1","checking","zhang-a",50000);
        assertEquals("AUTH_REQUIRED",assertThrows(BankException.class,()->bank.submit("u1",a.get("id").toString())).code);
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void confirmationHashCannotBeChanged() {
        var a=bank.preview("u1","checking","zhang-a",50000);
        assertEquals("CONFLICT",assertThrows(BankException.class,()->bank.authorize("u1",a.get("id").toString(),"tampered")).code);
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void anotherUserCannotReadOrAuthorizeAction() {
        var a=bank.preview("u1","checking","zhang-a",50000);
        assertThrows(BankException.class,()->bank.status("u2",a.get("id").toString()));
        assertThrows(BankException.class,()->bank.authorize("u2",a.get("id").toString(),a.get("payload_hash").toString()));
    }
    @Test void insufficientFundsAreRejectedWithoutDebit() {
        assertThrows(BankException.class,()->bank.preview("u1","checking","zhang-a",3000000));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void zeroAndNegativeAmountsAreRejected() {
        assertThrows(BankException.class,()->bank.preview("u1","checking","zhang-a",0));
        assertThrows(BankException.class,()->bank.preview("u1","checking","zhang-a",-1));
    }
    @Test void expiredAuthorizationCannotExecute() {
        var a=bank.preview("u1","checking","zhang-a",50000); String id=a.get("id").toString();
        bank.authorize("u1",id,a.get("payload_hash").toString());
        jdbc.update("UPDATE actions SET expires_at=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE id=?",id);
        assertEquals("AUTH_EXPIRED",assertThrows(BankException.class,()->bank.submit("u1",id)).code);
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void concurrentRepeatedSubmissionHasOnlyTwoLedgerEntries() throws Exception {
        var a=bank.preview("u1","checking","zhang-a",50000); String id=a.get("id").toString();
        bank.authorize("u1",id,a.get("payload_hash").toString());
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(executor.submit(()->bank.submit("u1",id)));
            for(var f:futures) f.get();
        }
        assertEquals(1193000,bank.balance("u1","checking"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT SUM(amount_minor) FROM ledger WHERE action_id=?",Long.class,id));
    }
    @Test void lostResponseCanBeQueriedAndRetriedWithoutDoubleDebit() {
        var a=bank.preview("u1","checking","zhang-a",50000); String id=a.get("id").toString();
        bank.authorize("u1",id,a.get("payload_hash").toString());
        bank.submit("u1",id); // discard the response as if connection lost
        assertEquals("VERIFIED_SUCCESS",bank.status("u1",id).get("status"));
        bank.submit("u1",id);
        assertEquals(1193000,bank.balance("u1","checking"));
    }
    @Test void ambiguousRecipientReturnsBothCandidates() { assertEquals(2,bank.recipients("u1","张三").size()); }
    @Test void anotherUserCannotReadBalance() { assertThrows(BankException.class,()->bank.balance("u2","checking")); }
    @Test void sourceCannotBelongToAnotherUser() { assertThrows(BankException.class,()->bank.preview("u2","checking","zhang-a",100)); }
    @Test void monthlySummarySeparatesIncomeAndSpending() {
        var s=bank.statement("u1","2026-09");
        assertEquals(340300L,((Number)s.get("expense_minor")).longValue());
        assertEquals(850000L,((Number)s.get("income_minor")).longValue());
    }
    @Test void revokeMandateDoesNotIncreaseBalanceOrCancelContract() {
        var a=bank.previewRevoke("u1","music"); String id=a.get("id").toString();
        bank.authorize("u1",id,a.get("payload_hash").toString());bank.submit("u1",id);
        assertEquals(1243000,bank.balance("u1","checking"));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT status FROM mandates WHERE id='music'",String.class));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT contract_status FROM mandates WHERE id='music'",String.class));
    }
    @Test void scheduleIsCreatedWithoutImmediateDebitAndRunsOnce() {
        var a=bank.previewSchedule("u1","checking","travel-recipient",8300,"2026-11-20","2027-10-20",20);
        String id=a.get("id").toString();bank.authorize("u1",id,a.get("payload_hash").toString());bank.submit("u1",id);
        assertEquals(1243000,bank.balance("u1","checking"));
        bank.runDue("u1","2026-11-20");bank.runDue("u1","2026-11-20");
        assertEquals(1234700,bank.balance("u1","checking"));
        assertEquals(5828300,bank.balance("u1","travel"));
    }
    @Test void scheduleDoesNotRunBeforeFirstDate() {
        var a=bank.previewSchedule("u1","checking","travel-recipient",8300,"2026-11-20","2027-10-20",20);
        String id=a.get("id").toString();bank.authorize("u1",id,a.get("payload_hash").toString());bank.submit("u1",id);
        assertTrue(bank.runDue("u1","2026-10-20").isEmpty());
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void simulatedScheduleBooksItsOccurrenceMonthAndBothLegs() {
        var a=bank.previewSchedule("u1","checking","travel-recipient",8300,"2026-11-20","2027-10-20",20);
        String id=a.get("id").toString();bank.authorize("u1",id,a.get("payload_hash").toString());bank.submit("u1",id);
        bank.runDue("u1","2026-11-20");
        assertEquals(2,((List<?>)bank.statement("u1","2026-11").get("transactions")).size());
        assertEquals(0L,((Number)bank.statement("u1","2026-11").get("expense_minor")).longValue());
        assertEquals(0L,((Number)bank.statement("u1","2026-11").get("income_minor")).longValue());
    }
    @Test void ownAccountTransferIsNotConsumption() {
        var a=bank.preview("u1","checking","travel-recipient",8300);String id=a.get("id").toString();
        bank.authorize("u1",id,a.get("payload_hash").toString());bank.submit("u1",id);
        String month=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        assertEquals(0L,((Number)bank.statement("u1",month).get("expense_minor")).longValue());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE merchant LIKE '转账 / %'",Integer.class));
    }
}
