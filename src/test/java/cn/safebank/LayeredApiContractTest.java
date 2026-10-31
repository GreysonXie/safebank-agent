package cn.safebank;
import cn.safebank.agent.BankToolGateway;
import cn.safebank.common.*;
import cn.safebank.vo.*;
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
import java.time.OffsetDateTime;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:layered-contract;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@AutoConfigureMockMvc
class LayeredApiContractTest extends BankTestFixture {
    @Autowired BankToolGateway tools;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired java.time.Clock clock;
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
        var statement=json.readTree(mvc.perform(get("/api/statement").param("month","2026-09").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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

    @Test void sessionAndStatementFollowTheRealBusinessDate() throws Exception {
        var initial=mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        var sessionView=json.readTree(initial.getResponse().getContentAsString());
        String businessDate=cn.safebank.common.SimulationClock.businessDate(clock).toString();
        String businessMonth=java.time.YearMonth.from(java.time.LocalDate.parse(businessDate)).toString();
        assertEquals(businessDate,sessionView.path("businessDate").asText());
        var current=json.readTree(mvc.perform(get("/api/statement").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(businessMonth,current.path("month").asText());
        var explicit=json.readTree(mvc.perform(get("/api/statement").param("month","2026-09").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("2026-09",explicit.path("month").asText());
        assertTrue(explicit.path("transactions").size()>0);
    }
    @Test void scheduleTransferHistoryKeepsScheduleAndActionFieldsFlat() throws Exception {
        var action=new ActionVO("action-1","u1",ActionKind.TRANSFER,"hash",ActionStatus.VERIFIED_SUCCESS,
            OffsetDateTime.parse("2026-10-20T00:00:00Z"),"receipt-1",null,
            new TransferParametersVO("checking","travel-recipient","旅行目标",5000,"CNY",0L,"schedule-1","2026-10-20"));
        var view=json.valueToTree(new ScheduleTransferVO("schedule-1",java.time.LocalDate.of(2026,10,20),action));
        assertEquals("schedule-1",view.path("scheduleId").asText());
        assertEquals("2026-10-20",view.path("dueDate").asText());
        assertEquals("action-1",view.path("id").asText());
        assertEquals("VERIFIED_SUCCESS",view.path("status").asText());
        assertEquals(5000,view.path("amount_minor").asLong());
        assertFalse(view.has("action"));
    }
}
