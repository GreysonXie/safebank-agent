package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.common.ContextContracts.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:contextApi;DB_CLOSE_DELAY=-1","bank.model.api-key="}) @AutoConfigureMockMvc
class ContextApiTest extends BankTestFixture {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ContextService contexts;MockHttpSession session;String csrf;
    @BeforeEach void initialize() throws Exception {var r=mvc.perform(get("/api/session")).andReturn();session=(MockHttpSession)r.getRequest().getSession();csrf=json.readTree(r.getResponse().getContentAsString()).path("csrf").asText();}
    @Test void chatIsBackwardCompatibleAndReplayUsesOriginalResponse() throws Exception {
        String body="{\"text\":\"分析2026-09账单\",\"requestId\":\"stable\"}";
        var first=mvc.perform(post("/api/chat").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.conversationId").isString()).andReturn().getResponse().getContentAsString();
        var replay=mvc.perform(post("/api/chat").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first),json.readTree(replay));String id=json.readTree(first).path("conversationId").asText();
        mvc.perform(get("/api/conversations/"+id).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.slots.primary_month").value("2026-09"));
        mvc.perform(post("/api/chat").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"text\":\"继续\",\"conversationId\":\""+id+"\",\"requestId\":\"second\",\"expectedContextVersion\":1}")).andExpect(status().isConflict());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
    @Test void unknownOwnerExpiryAndCsrfAreEnforced() throws Exception {
        String id=contexts.create("u2");mvc.perform(get("/api/conversations/"+id).session(session)).andExpect(status().isNotFound());
        id=contexts.create("u1");mvc.perform(post("/api/conversations/"+id+"/context/reset").session(session).contentType("application/json").content("{\"expectedContextVersion\":1}")).andExpect(status().isForbidden());
        db.update("UPDATE agent_conversations SET expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=?",id);mvc.perform(get("/api/conversations/"+id).session(session)).andExpect(status().isGone());
    }
    @Test void interruptedTurnExposesOriginalBankReference() throws Exception {
        var l=contexts.begin("u1",new ChatRequestDTO("原预览",5,null,"interrupted",null)).lease();
        var p=context.getBean(ContextBankProposalService.class);var a=json.valueToTree(p.preview(l,"original","preview_transfer",json.valueToTree(Map.of("source_id","checking","recipient_id","zhang-a","amount_minor",30000))));contexts.fail(l,"INTERRUPTED");
        mvc.perform(get("/api/conversations/"+l.conversationId()).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.bankReferences[0].bankObjectId").value(a.path("id").asText()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
}
