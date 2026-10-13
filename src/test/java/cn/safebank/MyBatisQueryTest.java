package cn.safebank;
import cn.safebank.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-query;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class MyBatisQueryTest extends BankTestFixture {
    @Autowired StatementService statements;
    @Autowired RecipientService recipients;
    @Autowired MandateService mandates;
    @Test void typedStatementKeepsAccountingAndOwnership() {
        var result=statements.statement("u1","2026-09");
        assertEquals(340300,result.expenseMinor());assertEquals(850000,result.incomeMinor());
        db.update("INSERT INTO transactions VALUES('refund','u1','checking','退款','退款',3000,DATE '2026-09-15')");
        db.update("INSERT INTO transactions VALUES('internal','u1','checking','本人转账','内部转账',-5000,DATE '2026-09-16')");
        result=statements.statement("u1","2026-09");
        assertEquals(340300,result.expenseMinor());assertEquals(853000,result.incomeMinor());assertEquals(5000,result.internalTransferMinor());
        assertEquals(0,statements.statement("u2","2026-09").expenseMinor());
        assertEquals("VALIDATION_ERROR",assertThrows(BankException.class,()->statements.statement("u1","invalid")).code);
    }
    @Test void ambiguousRecipientsAndRecurringCandidatesRemainDistinct() {
        assertEquals(2,recipients.search("u1","张三").size());
        assertTrue(recipients.search("u2","张三").isEmpty());
        assertEquals("NOT_FOUND",assertThrows(BankException.class,()->recipients.owned("u2","zhang-a")).code);
        assertEquals(2,mandates.list("u1").size());
        assertFalse(statements.recurring("u1").getFirst().usageKnown());
    }
}
