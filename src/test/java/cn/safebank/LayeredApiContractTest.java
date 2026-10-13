package cn.safebank;
import cn.safebank.agent.BankToolGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:layered-contract;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@AutoConfigureMockMvc
class LayeredApiContractTest extends BankTestFixture {
    @Autowired BankToolGateway tools;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Test void gatewayHasReadAndPreviewToolsButNoAuthorizationOrSubmission() {
        var statement=json.valueToTree(tools.execute("u1","get_statement",json.valueToTree(Map.of("month","2026-09"))));
        assertEquals(340300,statement.path("expense_minor").asLong());assertEquals("2026-09-01",statement.path("transactions").get(0).path("booked_on").asText());
        for(String forbidden:List.of("authorize","submit","cancel_action"))assertEquals("PERMISSION_DENIED",assertThrows(BankException.class,()->tools.execute("u1",forbidden,json.createObjectNode())).code);
    }
    @Test void controllersKeepFlatJsonNullsAndSessionProtection() throws Exception {
        var initial=mvc.perform(get("/api/session")).andReturn();var session=(MockHttpSession)initial.getRequest().getSession();
        String csrf=json.readTree(initial.getResponse().getContentAsString()).path("csrf").asText();
        mvc.perform(get("/api/accounts").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].balance_minor").value(1243000)).andExpect(jsonPath("$[0].user_id").doesNotExist());
        var preview=mvc.perform(post("/api/transfers/preview").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"sourceId\":\"checking\",\"recipientId\":\"zhang-a\",\"amountMinor\":50000}")).andExpect(status().isOk()).andReturn();
        var view=json.readTree(preview.getResponse().getContentAsString());assertTrue(view.has("receipt_id"));assertTrue(view.path("receipt_id").isNull());assertEquals(50000,view.path("amount_minor").asLong());assertFalse(view.has("parameters"));
        mvc.perform(post("/api/actions/"+view.path("id").asText()+"/authorize").session(session).contentType("application/json").content("{\"payloadHash\":\"x\",\"demoVerificationAccepted\":true}")).andExpect(status().isForbidden());
        var other=new MockHttpSession();other.setAttribute("user","u2");other.setAttribute("csrf","other");
        mvc.perform(get("/api/actions/"+view.path("id").asText()).session(other)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    private Set<String> fields(com.fasterxml.jackson.databind.JsonNode node) {
        var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return names;
    }
    @Test void statementObservationAndWorkflowKeepExactPublicFields() throws Exception {
        var initial=mvc.perform(get("/api/session")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        String csrf=json.readTree(initial.getResponse().getContentAsString()).path("csrf").asText();
        var statement=json.readTree(mvc.perform(get("/api/statement").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(Set.of("month","expense_minor","income_minor","internal_transfer_minor","categories","transactions","anomaly_candidates","anomaly_rule","usage_data_available"),fields(statement));
        assertEquals(Set.of("id","merchant","category","amount_minor","booked_on"),fields(statement.path("transactions").get(0)));
        var preview=json.readTree(mvc.perform(post("/api/transfers/preview").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"sourceId\":\"checking\",\"recipientId\":\"zhang-a\",\"amountMinor\":50000}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var observed=json.readTree(mvc.perform(get("/api/actions/"+preview.path("id").asText()).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(Set.of("id","user_id","kind","payload_hash","status","expires_at","receipt_id","error_code","source_id","recipient_id","recipient_label","amount_minor","currency","fee_minor","security"),fields(observed));
        assertTrue(observed.path("receipt_id").isNull());assertEquals("AWAITING_AUTH",observed.path("status").asText());
        var workflow=json.readTree(mvc.perform(post("/api/workflows").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"month\":\"2026-09\",\"mandateIds\":[\"music\"],\"sourceId\":\"checking\",\"recipientId\":\"travel-recipient\",\"firstDate\":\"2026-11-20\",\"endDate\":\"2027-10-20\",\"dayOfMonth\":20,\"onlyConfirmedSavings\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(Set.of("id","version","state","request","estimatedSavingMinor","partial","steps","pendingAction","analysis"),fields(workflow));
        assertEquals(Set.of("key","kind","status","actionId","inputVersion","savingMinor","errorCode"),fields(workflow.path("steps").get(0)));
        assertFalse(workflow.path("pendingAction").has("parameters"));
    }
}
