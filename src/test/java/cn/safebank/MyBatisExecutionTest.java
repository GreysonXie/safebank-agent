package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-execution;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class MyBatisExecutionTest extends BankTestFixture {
    @Autowired ActionService actions;
    @Autowired LocalBankExecutionService execution;
    @Test void crossOwnerRecipientAndRepeatedExecutionKeepBalancedLedger() {
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));
        bank.authorize("u1",a.id(),a.payloadHash());
        cn.safebank.common.RiskRejectedException.value(context.getBean(AuthorizationGateService.class).beginSubmission("u1",a.id(),cn.safebank.common.RiskTypes.RiskChannel.HTTP));
        assertEquals("VERIFIED_SUCCESS",execution.submit("u1",a.id()).status().name());execution.submit("u1",a.id());
        assertEquals(1193000,bank.balance("u1","checking"));
        assertEquals(50000,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='external-a'",Long.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,a.id()));
        assertEquals(0,db.queryForObject("SELECT SUM(amount_minor) FROM ledger WHERE action_id=?",Long.class,a.id()));
        assertEquals("NOT_FOUND",assertThrows(BankException.class,()->actions.previewTransfer("u1",new TransferPreviewRequestDTO("other","zhang-a",100))).code);
        db.update("DELETE FROM ledger WHERE action_id=? AND account_id='external-a'",a.id());
        assertEquals("CONFLICT",assertThrows(BankException.class,()->actions.status("u1",a.id())).code);
    }
    @Test void secondLedgerFailureRollsBackAllFinancialWrites() {
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));
        bank.authorize("u1",a.id(),a.payloadHash());
        cn.safebank.common.RiskRejectedException.value(context.getBean(AuthorizationGateService.class).beginSubmission("u1",a.id(),cn.safebank.common.RiskTypes.RiskChannel.HTTP));
        db.update("INSERT INTO ledger VALUES('injected-conflict',?,'external-a',50000)",a.id());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->execution.submit("u1",a.id()));
        assertEquals(1243000,bank.balance("u1","checking"));
        assertEquals(0,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='external-a'",Long.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,a.id()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM transactions WHERE id=?",Integer.class,a.id()+":debit"));
        assertEquals("AUTHORIZED",actions.status("u1",a.id()).status().name());
    }
}
