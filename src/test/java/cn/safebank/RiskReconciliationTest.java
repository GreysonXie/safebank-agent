package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.common.RiskTypes.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskReconciliation;DB_CLOSE_DELAY=-1","bank.simulation.faults-enabled=true"})
class RiskReconciliationTest extends BankTestFixture {
    @Autowired RiskReconciliationService reconciliation;@Autowired AuthorizationGateService gate;@Autowired ActionService actions;
    @Test void unknownLegacyDateSurvivesVerificationCooldown(){
        String id=bank.preview("u1","checking","zhang-a",50000).get("id").toString();db.update("UPDATE actions SET status='PENDING' WHERE id=?",id);db.update("INSERT INTO execution_observations VALUES(?,'u1','PENDING',NULL,NULL,CURRENT_TIMESTAMP)",id);
        var result=reconciliation.reconcileUser("u1");assertTrue(result.transfersLocked());assertEquals(1,result.unresolved());db.update("UPDATE risk_user_guards SET locked_until=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00Z' WHERE user_id='u1'");
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",1000));var r=gate.authorize("u1",a.id(),new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),true,null),RiskChannel.HTTP);
        assertEquals("RISK_RECONCILIATION_REQUIRED",r.failure().code());assertFalse(bank.accounts("u1").isEmpty());assertNull(db.queryForObject("SELECT business_date FROM risk_transfer_usage WHERE action_id=?",LocalDate.class,id));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));
    }
    @Test void legacySuccessCannotBecomeSyntheticAuthorization(){
        String id=bank.preview("u1","checking","zhang-a",50000).get("id").toString();db.update("UPDATE actions SET status='VERIFIED_SUCCESS' WHERE id=?",id);db.update("INSERT INTO transactions VALUES(?,'u1','checking','legacy transfer','转账',-50000,DATE '2026-10-08')",id+":debit");
        var result=reconciliation.reconcileUser("u1");assertFalse(result.transfersLocked());assertEquals(1,result.migrated());assertEquals("LEGACY_OBSERVED",db.queryForObject("SELECT provenance FROM risk_transfer_usage WHERE action_id=?",String.class,id));assertNull(db.queryForObject("SELECT authorization_id FROM risk_transfer_usage WHERE action_id=?",String.class,id));assertEquals("COMMITTED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,id));
    }
    @Test void legacyPendingCanFinishOnceWithoutFabricatedGrant(){
        String id=bank.preview("u1","checking","zhang-a",50000).get("id").toString();db.update("UPDATE actions SET status='PENDING',expires_at=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00Z' WHERE id=?",id);
        db.update("INSERT INTO execution_observations VALUES(?,'u1','PENDING',NULL,NULL,CURRENT_TIMESTAMP)",id);reconciliation.reconcileUser("u1");var faults=context.getBean(cn.safebank.bank.FaultInjectingBankAdapter.class);faults.configure("u1",id,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.PENDING_THEN_SUCCESS,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.NONE);
        var execution=context.getBean(ActionExecutionService.class);assertEquals("VERIFIED_SUCCESS",execution.observe("u1",id).action().status().name());execution.observe("u1",id);
        assertEquals(1193000,bank.balance("u1","checking"));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,id));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals("LEGACY_OBSERVED",db.queryForObject("SELECT provenance FROM risk_transfer_usage WHERE action_id=?",String.class,id));
    }
}
