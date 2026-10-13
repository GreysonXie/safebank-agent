package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.agent.*;
import cn.safebank.dto.*;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextReview;DB_CLOSE_DELAY=-1")
class ContextReviewRegressionTest extends BankTestFixture {
    @Autowired ContextService contexts; @Autowired ContextValidationService validation;
    @Autowired ContextBankProposalService proposals; @Autowired ContextAgentSupport support;
    @Autowired ContextEvidenceService evidence; @Autowired ObjectMapper json;
    @Autowired ActionService actions;
    @Test void negatedOrdinalAndNoteNeverBecomeUserSelection(){
        for(String text:List.of("不要第二个","不要备注同事的张三","不是zhang-b")){
            var l=contexts.begin("u1",new ChatRequestDTO(text,5,null,UUID.randomUUID().toString(),null)).lease();
            var s=contexts.snapshot("u1",l.conversationId());
            s=validation.withServerSlot(l,s,SlotName.RECIPIENT_CANDIDATES,json.valueToTree(List.of("zhang-a","zhang-b")),null);
            var oldSlots=new EnumMap<SlotName,SlotValue>(SlotName.class);oldSlots.putAll(s.slots());oldSlots.put(SlotName.SELECTED_RECIPIENT_ID,new SlotValue(json.valueToTree("zhang-b"),SlotProvenance.USER_EXPLICIT,l.turnId(),null));
            s=new ContextSnapshot(s.conversationId(),s.version(),s.intent(),s.state(),oldSlots,s.proposal(),s.evidence(),s.displayHistory());
            s=validation.stage(l,s,text,new ContextProposalDTO(Intent.TRANSFER_DRAFT,Map.of("selected_recipient_id",json.valueToTree("zhang-b")),List.of(),List.of(),false));
            assertEquals(SlotProvenance.MODEL_PROPOSED,s.slots().get(SlotName.SELECTED_RECIPIENT_ID).provenance(),text);
        }
    }
    @Test void changingRecipientCancelsAuthorizedPreviewAndBlocksUnknown(){
        for(boolean unknown:List.of(false,true)){
            var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",30000));
            bank.authorize("u1",a.id(),a.payloadHash());
            if(unknown)db.update("UPDATE actions SET status='UNKNOWN' WHERE id=?",a.id());
            String text="改为备注同事的张三";
            var l=contexts.begin("u1",new ChatRequestDTO(text,5,null,UUID.randomUUID().toString(),null)).lease();
            var base=contexts.snapshot("u1",l.conversationId());
            var slots=new EnumMap<SlotName,SlotValue>(SlotName.class);
            slots.put(SlotName.SELECTED_RECIPIENT_ID,new SlotValue(json.valueToTree("zhang-a"),SlotProvenance.USER_EXPLICIT,l.turnId(),null));
            slots.put(SlotName.ACTIVE_ACTION_ID,new SlotValue(json.valueToTree(a.id()),SlotProvenance.BANK_CONFIRMED,l.turnId(),null));
            var s=new ContextSnapshot(base.conversationId(),base.version(),Intent.TRANSFER_DRAFT,ConversationState.AWAITING_CONFIRMATION,slots,null,List.of(),List.of());
            var p=new ContextProposalDTO(Intent.TRANSFER_DRAFT,Map.of("selected_recipient_id",json.valueToTree("zhang-b")),List.of(),List.of(),false);
            if(unknown){var ex=assertThrows(BankException.class,()->validation.stage(l,s,text,p));assertEquals("CONFLICT",ex.code);}
            else {var next=validation.stage(l,s,text,p);assertEquals("CANCELLED",actions.status("u1",a.id()).status().name());assertFalse(next.slots().containsKey(SlotName.ACTIVE_ACTION_ID));assertThrows(BankException.class,()->bank.submit("u1",a.id()));}
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
    @Test void identicalWorkflowAcrossTurnsUsesOriginalBankObject(){
        var l=contexts.begin("u1",new ChatRequestDTO("储蓄预览",5,null,"first",null)).lease();
        var args=json.valueToTree(Map.of("month","2026-09","mandate_ids",List.of("music"),"source_id","checking","recipient_id","travel-recipient","first_date","2026-11-20","end_date","2027-05-20","day_of_month",20,"only_confirmed_savings",true));
        var first=json.valueToTree(proposals.preview(l,"w1","start_savings_workflow",args));
        contexts.fail(l,"RESPONSE_LOST");
        var next=contexts.begin("u1",new ChatRequestDTO("继续原储蓄预览",5,l.conversationId(),"second",l.inputVersion())).lease();
        var repeat=json.valueToTree(proposals.preview(next,"w2","start_savings_workflow",args));
        assertEquals(first.path("id"),repeat.path("id"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM workflows",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void restoredQueryFactsKeepOriginalTimeScopeAndAction(){
        var l=contexts.begin("u1",new ChatRequestDTO("分析代扣",5,null,"metadata",null)).lease();
        var at=java.time.Instant.parse("2026-10-09T12:34:56.123456789Z");
        evidence.store(l,List.of(new EvidenceInput("query_mandates:original","list_mandates","original",json.valueToTree(List.of(Map.of("id","music","merchant","原查询音乐","amount_minor",1500,"billing_interval","MONTHLY","status","ACTIVE"))),at,null)));
        db.update("UPDATE mandates SET merchant='changed' WHERE user_id='u1'");
        support.open(l,contexts.snapshot("u1",l.conversationId()),"分析代扣");
        try{
            String catalog=json.valueToTree(new GroundedAnswer(json,support.remembered()).modelReviewCatalog()).toString();
            assertTrue(catalog.contains(at.toString()),catalog);assertTrue(catalog.contains("original"));assertTrue(catalog.contains("OWN_BANK_MANDATES"));assertFalse(catalog.contains("changed"));
            assertTrue(json.valueToTree(support.messages()).toString().contains(at.toString()));
        }finally{support.close();}
    }
}
