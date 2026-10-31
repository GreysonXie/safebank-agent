package cn.safebank;
import cn.safebank.bank.FaultInjectingBankAdapter;
import static cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:execution;DB_CLOSE_DELAY=-1","bank.simulation.faults-enabled=true"})
class ActionExecutionServiceTest extends BankTestFixture {
    @Autowired ExecutionTestSupport execution;
    @Autowired FaultInjectingBankAdapter faults;
    @Test void committedButLostResponseIsUnknownUntilLookupAndNeverDebitsTwice() {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));
        faults.configure("u1",id,AFTER_COMMIT_RESPONSE_LOST,NONE);
        assertEquals("UNKNOWN",execution.submit("u1",id).get("status"));
        assertEquals(1193000,bank.balance("u1","checking"));
        assertEquals("VERIFIED_SUCCESS",execution.observe("u1",id).get("status"));
        execution.submit("u1",id);
        assertEquals(1193000,bank.balance("u1","checking"));
    }
    @Test void rejectionBeforeBankExecutionDoesNotMoveMoney() {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));
        faults.configure("u1",id,BEFORE_SUBMIT_FAILURE,NONE);
        assertEquals("VERIFIED_FAILED",execution.submit("u1",id).get("status"));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void acceptedPendingIsCompletedByBankAndCannotBeCancelled() {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));
        faults.configure("u1",id,PENDING_THEN_SUCCESS,NONE);
        assertEquals("PENDING",execution.submit("u1",id).get("status"));
        assertEquals(1243000,bank.balance("u1","checking"));
        assertThrows(BankException.class,()->bank.cancel("u1",id));
        assertEquals("VERIFIED_SUCCESS",execution.observe("u1",id).get("status"));
        assertEquals(1193000,bank.balance("u1","checking"));
    }
    @Test void unavailableLookupBlocksFurtherSubmission() {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));
        faults.configure("u1",id,AFTER_COMMIT_RESPONSE_LOST,LOOKUP_UNAVAILABLE);
        execution.submit("u1",id);
        assertEquals("UNKNOWN",execution.submit("u1",id).get("status"));
        assertEquals(1193000,bank.balance("u1","checking"));
        faults.configure("u1",id,AFTER_COMMIT_RESPONSE_LOST,NONE);
        assertEquals("VERIFIED_SUCCESS",execution.observe("u1",id).get("status"));
    }
    @Test void observationAndFaultConfigurationEnforceOwnership() {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));
        assertThrows(BankException.class,()->execution.observe("u2",id));
        assertThrows(BankException.class,()->faults.configure("u2",id,NONE,NONE));
    }
    @Test void pendingTransferRejectedAfterFundsChangeHasTerminalFailedReceipt() {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));faults.configure("u1",id,PENDING_THEN_SUCCESS,NONE);
        execution.submit("u1",id);db.update("UPDATE accounts SET balance_minor=100 WHERE id='checking'");
        var failed=execution.observe("u1",id);assertEquals("VERIFIED_FAILED",failed.get("status"));assertEquals("INSUFFICIENT_FUNDS",failed.get("error_code"));
        assertEquals("VERIFIED_FAILED",execution.submit("u1",id).get("status"));assertEquals(100,bank.balance("u1","checking"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,id));
    }
}
