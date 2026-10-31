package cn.safebank;

import cn.safebank.common.*;
import cn.safebank.dto.*;
import cn.safebank.service.*;
import cn.safebank.vo.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:productManagement;DB_CLOSE_DELAY=-1")
@Import(RiskConcurrencyTest.Clocks.class)
class ProductManagementTest extends BankTestFixture {
    @Autowired ActionService actions;
    @Autowired ScheduleService schedules;
    @Autowired MutableRiskClock clock;
    @BeforeEach void time(){clock.set(Instant.parse("2026-10-09T04:00:00Z"));}
    private ActionVO execute(ActionVO preview){bank.authorize("u1",preview.id(),preview.payloadHash());bank.submit("u1",preview.id());return actions.status("u1",preview.id());}
    private String plan(int day,String first){var a=execute(actions.previewSchedule("u1",new SchedulePreviewRequestDTO("checking","travel-recipient",5000,first,"2027-09-30",day)));assertEquals(ActionStatus.VERIFIED_SUCCESS,a.status());return a.receiptId();}
    private ActionVO manage(String id,String operation){return execute(actions.previewScheduleManagement("u1",id,new ScheduleManageRequestDTO(operation,null,null,null,null,null,null)));}
    @Test void dayThirtyOneRunsOnceAtShortMonthEndAndKeepsDoubleEntryLedger(){
        String id=plan(31,"2026-10-31");
        assertTrue(schedules.runDue("u1","2027-02-27").isEmpty());
        var first=schedules.runDue("u1","2027-02-28").getFirst();
        assertEquals(ActionStatus.VERIFIED_SUCCESS,first.status());
        assertEquals(first.id(),schedules.runDue("u1","2027-02-28").getFirst().id());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,first.id()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM occurrences WHERE schedule_id=?",Integer.class,id));
    }
    @Test void changingDayInSameMonthNeverCreatesSecondDebitAndHistoryKeepsOriginalAmount(){
        String id=plan(9,"2026-11-09");var original=schedules.runDue("u1","2026-11-09").getFirst();
        var edit=execute(actions.previewScheduleManagement("u1",id,new ScheduleManageRequestDTO("EDIT","checking","travel-recipient",7000L,"2026-11-20","2027-09-20",20)));
        assertEquals(ActionStatus.VERIFIED_SUCCESS,edit.status());
        assertEquals(original.id(),schedules.runDue("u1","2026-11-20").getFirst().id());
        assertEquals(1238000,bank.balance("u1","checking"));
        assertEquals(5000,((TransferParametersVO)schedules.transfers("u1").getFirst().action().parameters()).amountMinor());
        var december=schedules.runDue("u1","2026-12-20").getFirst();assertEquals(ActionStatus.VERIFIED_SUCCESS,december.status());
        assertEquals(1231000,bank.balance("u1","checking"));
    }
    @Test void pauseResumeCancelRequireConfirmationAndStalePreviewCannotOverwriteChanges(){
        String id=plan(20,"2026-11-20");var stale=actions.previewScheduleManagement("u1",id,new ScheduleManageRequestDTO("CANCEL",null,null,null,null,null,null));
        assertEquals("ACTIVE",schedules.list("u1").getFirst().status());
        assertEquals(ActionStatus.VERIFIED_SUCCESS,manage(id,"PAUSE").status());assertTrue(schedules.runDue("u1","2026-11-20").isEmpty());
        assertEquals(ActionStatus.VERIFIED_FAILED,execute(stale).status());assertEquals("PAUSED",schedules.list("u1").getFirst().status());
        assertEquals(ActionStatus.VERIFIED_SUCCESS,manage(id,"RESUME").status());assertEquals("ACTIVE",schedules.list("u1").getFirst().status());
        assertEquals(ActionStatus.VERIFIED_SUCCESS,manage(id,"CANCEL").status());assertTrue(schedules.runDue("u1","2026-12-20").isEmpty());
    }
    @Test void editWithdrawsOldUnsubmittedOccurrenceButNeverCreatesReplacementInSameMonth(){
        var p=execute(actions.previewSchedule("u1",new SchedulePreviewRequestDTO("checking","zhang-a",150000,"2026-11-09","2027-09-09",9)));
        var occurrence=schedules.runDue("u1","2026-11-09").getFirst();assertEquals(ActionStatus.AWAITING_AUTH,occurrence.status());
        execute(actions.previewScheduleManagement("u1",p.receiptId(),new ScheduleManageRequestDTO("EDIT","checking","travel-recipient",5000L,"2026-11-20","2027-09-20",20)));
        assertEquals(ActionStatus.CANCELLED,actions.status("u1",occurrence.id()).status());
        assertEquals(occurrence.id(),schedules.runDue("u1","2026-11-20").getFirst().id());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
    @Test void activatingServiceCreatesOnlyAuthorizationAndPreventsDuplicatePreviewRace(){
        var r=new MandateActivationRequestDTO(null,"测试云盘",1000,"MONTHLY","2026-11-01");
        var a=actions.previewMandateActivation("u1",r);var duplicate=actions.previewMandateActivation("u1",r);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM mandates WHERE merchant='测试云盘'",Integer.class));
        assertEquals(ActionStatus.VERIFIED_SUCCESS,execute(a).status());assertEquals(ActionStatus.VERIFIED_FAILED,execute(duplicate).status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM mandates WHERE merchant='测试云盘'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));assertEquals(1243000,bank.balance("u1","checking"));
        assertThrows(BankException.class,()->actions.previewMandateActivation("u1",r));
    }
    @Test void revokedServiceCanBeReopenedAndOtherUsersCannotManageOwnedPlan(){
        var revoke=actions.previewRevoke("u1",new MandatePreviewRequestDTO("music"));execute(revoke);
        assertEquals(ActionStatus.VERIFIED_SUCCESS,execute(actions.previewMandateActivation("u1",new MandateActivationRequestDTO("music","音乐服务",1500,"MONTHLY","2026-11-01"))).status());
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM mandates WHERE id='music'",String.class));
        String id=plan(20,"2026-11-20");assertThrows(BankException.class,()->actions.previewScheduleManagement("u2",id,new ScheduleManageRequestDTO("CANCEL",null,null,null,null,null,null)));
    }
    @Test void pastFirstDateIsRejectedAndLeapYearUsesActualMonthEnd(){
        assertThrows(BankException.class,()->actions.previewSchedule("u1",new SchedulePreviewRequestDTO("checking","travel-recipient",5000,"2026-09-30","2027-01-31",31)));
        assertTrue(ScheduleRules.isDue(LocalDate.of(2028,2,29),31));assertFalse(ScheduleRules.isDue(LocalDate.of(2028,2,28),31));
    }
}
