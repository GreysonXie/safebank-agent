package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.ChatRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextProposal;DB_CLOSE_DELAY=-1")
class ContextProposalRecoveryTest {
    @Autowired ContextService contexts;@Autowired ContextBankProposalService proposals;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @Test void failedConversationStillRecoversSameUnsubmittedAction() throws Exception {
        var l=contexts.begin("u1",new ChatRequestDTO("给张三预览转300元",5,null,"preview",null)).lease();
        var args=json.readTree("{\"source_id\":\"checking\",\"recipient_id\":\"zhang-a\",\"amount_minor\":30000}");
        var first=json.valueToTree(proposals.preview(l,"transfer1","preview_transfer",args));
        var repeat=json.valueToTree(proposals.preview(l,"transfer1","preview_transfer",args));
        assertEquals(first.path("id"),repeat.path("id"));
        assertThrows(BankException.class,()->proposals.preview(l,"transfer1","preview_transfer",json.readTree("{\"source_id\":\"checking\",\"recipient_id\":\"zhang-a\",\"amount_minor\":40000}")));
        contexts.fail(l,"SIMULATED_INTERRUPTION");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
        assertNotNull(db.queryForObject("SELECT bank_references_json FROM agent_turn_receipts WHERE id=?",String.class,l.turnId()));
    }
}
