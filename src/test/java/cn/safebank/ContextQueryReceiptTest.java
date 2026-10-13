package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.common.RiskTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextQuery;DB_CLOSE_DELAY=-1")
class ContextQueryReceiptTest {
    @Autowired ContextService contexts;@Autowired ContextBankProposalService proposals;@Autowired ContextQueryReceiptService snapshots;
    @Autowired RiskQueryService queries;@Autowired AuthorizationGateService gate;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @Test void confirmedSnapshotSurvivesResponseLossWithoutReadingNewMandates(){
        var l=contexts.begin("u1",new ChatRequestDTO("查代扣",5,null,"query",null)).lease();
        var preview=json.valueToTree(proposals.preview(l,"query1","list_mandates",json.createObjectNode())).path("pendingAction");
        String id=preview.path("id").asText();var action=queriesAction(id);
        assertTrue(gate.authorize("u1",id,new AuthorizationRequestDTO(action.payloadHash(),action.security().decisionId(),true,null),RiskChannel.HTTP).accepted());
        var first=queries.executeAuthorized("u1",id);assertNotNull(first.data());
        var snapshot=snapshots.readOriginal("u1",l.conversationId(),id).orElseThrow();assertEquals(first.queriedAt(),snapshot.observedAt());assertEquals(first.data().size(),snapshot.data().size());
        db.update("UPDATE mandates SET merchant='changed' WHERE user_id='u1'");
        assertEquals(snapshot.data(),snapshots.readOriginal("u1",l.conversationId(),id).orElseThrow().data());
        assertNull(queries.executeAuthorized("u1",id).data());
        assertTrue(snapshots.readOriginal("u2",l.conversationId(),id).isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
    @Autowired ActionService actions;
    cn.safebank.vo.ActionVO queriesAction(String id){return actions.status("u1",id);}
}
