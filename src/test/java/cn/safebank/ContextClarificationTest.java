package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextClarification;DB_CLOSE_DELAY=-1")
class ContextClarificationTest extends BankTestFixture {
    @Autowired ContextService contexts;@Autowired ContextValidationService validation;@Autowired ObjectMapper json;@Autowired ActionService actions;
    @Autowired WorkflowService workflows;@Autowired MandateService mandates;
    @Test void explicitAmountMatchesEquivalentModelJsonInteger(){
        String text="从日常账户给备注同学的张三转300元";var l=contexts.begin("u1",new ChatRequestDTO(text,5,null,"integer",null)).lease();
        var s=validation.infer(l,contexts.snapshot("u1",l.conversationId()),text);
        s=validation.stage(l,s,text,new ContextProposalDTO(Intent.TRANSFER_DRAFT,Map.of("selected_recipient_id",json.valueToTree("zhang-a"),"source_account_id",json.valueToTree("checking")),List.of(),List.of(),false));
        var ready=s;assertDoesNotThrow(()->validation.requirePreview(l,ready,"preview_transfer",json.valueToTree(Map.of("source_id","checking","recipient_id","zhang-a","amount_minor",30000))));
    }
    @Test void keepingVideoRevisesOnlyUnfinishedWorkflow(){
        var w=workflows.create("u1",new WorkflowRequestDTO("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-05-20",20,true));
        var l=contexts.begin("u1",new ChatRequestDTO("视频保留",5,null,"keepVideo",null)).lease();var s=contexts.snapshot("u1",l.conversationId());
        var slots=new EnumMap<SlotName,SlotValue>(SlotName.class);slots.put(SlotName.ACTIVE_WORKFLOW_ID,new SlotValue(json.valueToTree(w.id().toString()),SlotProvenance.BANK_CONFIRMED,l.turnId(),null));slots.put(SlotName.SELECTED_MANDATE_IDS,new SlotValue(json.valueToTree(List.of("music","video")),SlotProvenance.USER_EXPLICIT,l.turnId(),null));
        var e=new EvidenceInput("query_mandates:test","list_mandates","test",json.valueToTree(mandates.list("u1")),java.time.Instant.now(),null);
        s=new ContextSnapshot(s.conversationId(),s.version(),Intent.SAVINGS_WORKFLOW,s.state(),slots,null,List.of(e),List.of());
        validation.stage(l,s,"视频保留",new ContextProposalDTO(Intent.SAVINGS_WORKFLOW,Map.of("excluded_mandate_ids",json.valueToTree(List.of("video"))),List.of(),List.of(),false));
        assertEquals(List.of("music"),workflows.get("u1",w.id()).request().mandateIds());assertEquals("ACTIVE",mandates.owned("u1","video").getStatus());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
    @Test void ambiguousRecipientCannotBecomeExecutableByModelChoice(){
        var l=contexts.begin("u1",new ChatRequestDTO("给张三转300元",5,null,"ambiguous",null)).lease();
        var p=new ContextProposalDTO(Intent.TRANSFER_DRAFT,Map.of("selected_recipient_id",json.valueToTree("zhang-a")),List.of(),List.of(),false);
        var s=validation.stage(l,contexts.snapshot("u1",l.conversationId()),"给张三转300元",p);
        assertEquals(SlotProvenance.MODEL_PROPOSED,s.slots().get(SlotName.SELECTED_RECIPIENT_ID).provenance());
        assertThrows(BankException.class,()->validation.requirePreview(l,s,"preview_transfer",json.valueToTree(Map.of("source_id","checking","recipient_id","zhang-a","amount_minor",30000))));
    }
    @Test void keepingMusicDoesNotBecomeExplicitRevocation(){
        var l=contexts.begin("u1",new ChatRequestDTO("音乐保留，不取消",5,null,"keep",null)).lease();var s=contexts.snapshot("u1",l.conversationId());
        var e=new EvidenceInput("query_mandates:test","list_mandates","test",json.valueToTree(List.of(Map.of("id","music","merchant","星河音乐"))),java.time.Instant.now(),null);
        s=new ContextSnapshot(s.conversationId(),s.version(),Intent.MANDATE_REVOKE_DRAFT,s.state(),s.slots(),null,List.of(e),List.of());
        var staged=validation.stage(l,s,"音乐保留，不取消",new ContextProposalDTO(Intent.MANDATE_REVOKE_DRAFT,Map.of("selected_mandate_ids",json.valueToTree(List.of("music"))),List.of(),List.of(),false));
        assertEquals(SlotProvenance.MODEL_PROPOSED,staged.slots().get(SlotName.SELECTED_MANDATE_IDS).provenance());
    }
    @Test void amountRevisionAndPauseCancelOnlyUnsubmittedPreview(){
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));
        var l=contexts.begin("u1",new ChatRequestDTO("改成300，先别转",5,null,"pause",null)).lease();var orig=contexts.snapshot("u1",l.conversationId());
        var values=new EnumMap<SlotName,SlotValue>(SlotName.class);values.put(SlotName.ACTIVE_ACTION_ID,new SlotValue(json.valueToTree(a.id()),SlotProvenance.BANK_CONFIRMED,l.turnId(),null));values.put(SlotName.AMOUNT_MINOR,new SlotValue(json.valueToTree(50000),SlotProvenance.USER_EXPLICIT,l.turnId(),null));
        orig=new ContextSnapshot(orig.conversationId(),orig.version(),Intent.TRANSFER_DRAFT,ConversationState.AWAITING_CONFIRMATION,values,null,List.of(),List.of());
        var next=validation.stage(l,orig,"改成300，先别转",new ContextProposalDTO(Intent.TRANSFER_DRAFT,Map.of("amount_minor",json.valueToTree(30000)),List.of(),List.of(),true));
        assertEquals(30000,next.slots().get(SlotName.AMOUNT_MINOR).value().longValue());assertEquals(ConversationState.PAUSED,next.state());assertEquals("CANCELLED",actions.status("u1",a.id()).status().name());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
}
