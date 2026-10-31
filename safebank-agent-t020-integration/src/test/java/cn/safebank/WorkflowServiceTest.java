package cn.safebank;
import static cn.safebank.WorkflowTestContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1","bank.simulation.faults-enabled=true"})
@org.springframework.context.annotation.Import(TestClockConfiguration.class)
class WorkflowServiceTest extends BankTestFixture {
    @Autowired WorkflowTestSupport workflows;
    @Autowired ExecutionTestSupport execution;
    @Autowired cn.safebank.bank.FaultInjectingBankAdapter faults;
    WorkflowRequest request() { return new WorkflowRequest("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true); }
    WorkflowView confirm(WorkflowView workflow) {
        String id=authorize(workflow.pendingAction());execution.submit("u1",id);return workflows.resume("u1",workflow.id(),workflow.version());
    }
    @Test void threeScenarioWorkflowPausesForEachActionAndRegisters83YuanSchedule() {
        var w=workflows.create("u1",request());assertEquals("AWAITING_AUTH",w.state());
        assertEquals("REVOKE",w.pendingAction().get("kind"));
        String first=w.pendingAction().get("id").toString();
        assertEquals(first,workflows.resume("u1",w.id(),w.version()).pendingAction().get("id"));
        w=confirm(w);assertEquals(1500,w.estimatedSavingMinor());
        w=confirm(w);assertEquals(8300,w.estimatedSavingMinor());
        assertEquals("SCHEDULE",w.pendingAction().get("kind"));
        assertEquals(8300L,((Number)w.pendingAction().get("amount_minor")).longValue());
        w=confirm(w);assertEquals("VERIFIED_SUCCESS",w.state());
        assertEquals(1243000,bank.balance("u1","checking"));assertEquals(1,bank.schedules("u1").size());
    }
    @Test void failedVideoOnlyAllows15YuanSchedule() {
        var w=confirm(workflows.create("u1",request()));
        var id=authorize(w.pendingAction());faults.configure("u1",id,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.BEFORE_SUBMIT_FAILURE,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.NONE);
        execution.submit("u1",id);w=workflows.resume("u1",w.id(),w.version());
        assertEquals(1500,w.estimatedSavingMinor());assertEquals(1500L,((Number)w.pendingAction().get("amount_minor")).longValue());
        w=confirm(w);assertEquals("PARTIAL_SUCCESS",w.state());assertTrue(w.partial());
    }
    @Test void parameterRevisionCancelsAuthorizedOldScheduleAndRejectsStaleVersion() {
        var w=confirm(confirm(workflows.create("u1",request())));String old=authorize(w.pendingAction());
        var r=request();var changed=new WorkflowRequest(r.month(),r.mandateIds(),r.sourceId(),"zhang-a",r.firstDate(),r.endDate(),r.dayOfMonth(),true);
        var updated=workflows.revise("u1",w.id(),w.version(),changed);
        assertEquals(2,updated.version());assertEquals("CANCELLED",bank.status("u1",old).get("status"));
        assertNotEquals(old,updated.pendingAction().get("id"));assertEquals("zhang-a",updated.pendingAction().get("recipient_id"));
        assertThrows(BankException.class,()->execution.submit("u1",old));
        assertEquals("CONFLICT",assertThrows(BankException.class,()->workflows.resume("u1",updated.id(),1)).code);
    }
    @Test void cancellationKeepsCompletedRevocationAndCannotUndoIt() {
        var w=confirm(workflows.create("u1",request()));String pending=w.pendingAction().get("id").toString();
        var result=workflows.cancel("u1",w.id(),w.version());assertEquals("CANCELLED",result.state());
        assertEquals("REVOKED",bank.mandates("u1").stream().filter(m->m.get("id").equals("music")).findFirst().orElseThrow().get("status"));
        assertEquals("CANCELLED",bank.status("u1",pending).get("status"));assertEquals(1500,result.estimatedSavingMinor());
    }
    @Test void unknownUpstreamStopsDownstreamAndRestorationUsesSameAction() {
        var w=workflows.create("u1",request());String id=authorize(w.pendingAction());
        var mode=cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.AFTER_COMMIT_RESPONSE_LOST;
        faults.configure("u1",id,mode,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.LOOKUP_UNAVAILABLE);execution.submit("u1",id);
        var unknown=workflows.resume("u1",w.id(),1);assertEquals("UNKNOWN",unknown.state());assertEquals(0,unknown.estimatedSavingMinor());assertEquals(1,unknown.steps().size());
        assertThrows(BankException.class,()->workflows.revise("u1",w.id(),1,request()));
        assertEquals(id,workflows.get("u1",w.id()).pendingAction().get("id"));
        faults.configure("u1",id,mode,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.NONE);
        var recovered=workflows.resume("u1",w.id(),1);assertEquals(1500,recovered.estimatedSavingMinor());assertEquals(2,recovered.steps().size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit WHERE action_id=? AND event='EXECUTED'",Integer.class,id));
    }
    @Test void crossUserCannotReadResumeReviseOrCancelWorkflow() {
        var w=workflows.create("u1",request());
        assertThrows(BankException.class,()->workflows.get("u2",w.id()));assertThrows(BankException.class,()->workflows.resume("u2",w.id(),1));
        assertThrows(BankException.class,()->workflows.revise("u2",w.id(),1,request()));assertThrows(BankException.class,()->workflows.cancel("u2",w.id(),1));assertTrue(workflows.list("u2").isEmpty());
    }
    @Test void rejectsUnknownBillingCycleAndUnconfirmedSavingBasis() {
        db.update("UPDATE mandates SET billing_interval='UNKNOWN' WHERE id='music'");assertThrows(BankException.class,()->workflows.create("u1",request()));
        var r=request();assertThrows(BankException.class,()->workflows.create("u1",new WorkflowRequest(r.month(),r.mandateIds(),r.sourceId(),r.recipientId(),r.firstDate(),r.endDate(),20,false)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class));
    }
    @Test void expiredPreviewPreservesOriginalActionAndVersionForRiskRefresh() {
        var w=workflows.create("u1",request());String old=w.pendingAction().get("id").toString();
        db.update("UPDATE actions SET expires_at='2000-01-01 00:00:00+00' WHERE id=?",old);
        var next=workflows.resume("u1",w.id(),1);assertEquals(1,next.version());assertEquals(old,next.pendingAction().get("id"));
        assertEquals("AWAITING_AUTH",bank.status("u1",old).get("status"));
    }
    @Test void anotherWorkflowCannotClaimAlreadyRevokedMandateAsItsSaving() {
        var first=workflows.create("u1",request());var second=workflows.create("u1",request());confirm(first);
        var result=confirm(second);assertEquals(0,result.estimatedSavingMinor());assertEquals("VERIFIED_FAILED",result.steps().getFirst().status());
    }
    @Test void cancellingUnknownStopsFutureStepsButReconcilesOriginalAction() {
        var w=workflows.create("u1",request());String id=authorize(w.pendingAction());
        var lost=cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.AFTER_COMMIT_RESPONSE_LOST;
        faults.configure("u1",id,lost,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.LOOKUP_UNAVAILABLE);execution.submit("u1",id);workflows.resume("u1",w.id(),1);
        var cancelled=workflows.cancel("u1",w.id(),1);assertEquals("CANCEL_REQUESTED",cancelled.state());assertEquals(1,cancelled.steps().size());
        faults.configure("u1",id,lost,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.NONE);
        var reconciled=workflows.resume("u1",w.id(),1);assertEquals("CANCELLED",reconciled.state());assertEquals(1500,reconciled.estimatedSavingMinor());assertEquals(1,reconciled.steps().size());assertTrue(bank.schedules("u1").isEmpty());
    }
    @Test void acceptedPendingRevokeLaterRejectedIsRecordedAsDefinitiveFailure() {
        var w=workflows.create("u1",request());String id=authorize(w.pendingAction());
        faults.configure("u1",id,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.PENDING_THEN_SUCCESS,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.NONE);execution.submit("u1",id);
        execution.submit("u1",authorize(bank.previewRevoke("u1","music")));
        var result=workflows.resume("u1",w.id(),1);assertEquals("VERIFIED_FAILED",result.steps().getFirst().status());assertEquals(0,result.estimatedSavingMinor());assertEquals(2,result.steps().size());
    }
}
