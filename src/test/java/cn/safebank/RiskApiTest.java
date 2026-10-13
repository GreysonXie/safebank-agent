package cn.safebank;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskApi;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@AutoConfigureMockMvc
class RiskApiTest extends BankTestFixture {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;MockHttpSession session;String csrf;
    @BeforeEach void session() throws Exception {var r=mvc.perform(get("/api/session")).andReturn();session=(MockHttpSession)r.getRequest().getSession();csrf=json.readTree(r.getResponse().getContentAsString()).path("csrf").asText();}
    private JsonNode preview(long amount) throws Exception {return json.readTree(mvc.perform(post("/api/transfers/preview").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"sourceId\":\"checking\",\"recipientId\":\"zhang-a\",\"amountMinor\":"+amount+",\"userId\":\"u2\",\"riskLevel\":\"GREEN\",\"businessDate\":\"2000-01-01\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    @Test void apiIgnoresClientIdentityRiskAndDate() throws Exception {var a=preview(150000);assertEquals("u1",a.path("user_id").asText());assertEquals("RED",a.path("security").path("riskLevel").asText());assertNotEquals("2000-01-01",a.path("security").path("businessDate").asText());mvc.perform(post("/api/actions/"+a.path("id").asText()+"/authorize").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("payloadHash",a.path("payload_hash").asText(),"decisionId",a.path("security").path("decisionId").asText(),"userConfirmed",true,"riskLevel","GREEN")))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED")).andExpect(jsonPath("$.security.riskLevel").value("RED"));assertEquals(1243000,bank.balance("u1","checking"));}
    @Test void csrfStillRequiredForChallengeAndAuthorization() throws Exception {var a=preview(150000);String id=a.path("id").asText();mvc.perform(post("/api/actions/"+id+"/verification-challenges").session(session).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(post("/api/actions/"+id+"/authorize").session(session).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
    @Test void oldCheckboxCannotAuthorize() throws Exception {var a=preview(50000);mvc.perform(post("/api/actions/"+a.path("id").asText()+"/authorize").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("payloadHash",a.path("payload_hash").asText(),"demoVerificationAccepted",true)))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RISK_DECISION_STALE"));}
    @Test void getRiskAndSummaryDoNotAuthorize() throws Exception {var a=preview(50000);int before=db.queryForObject("SELECT COUNT(*) FROM risk_decisions",Integer.class);mvc.perform(get("/api/actions/"+a.path("id").asText()+"/risk").session(session)).andExpect(status().isOk());mvc.perform(get("/api/risk/summary").session(session)).andExpect(status().isOk());assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM risk_decisions",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));}
    @Test void queryConfirmationDoesNotBlockPageInitialization() throws Exception {mvc.perform(get("/api/accounts").session(session)).andExpect(status().isOk());mvc.perform(get("/api/mandates").session(session)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUERY_CONFIRMATION_REQUIRED"));mvc.perform(post("/api/query-intents").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"operation\":\"QUERY_MANDATES\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("QUERY_MANDATES"));}
}
