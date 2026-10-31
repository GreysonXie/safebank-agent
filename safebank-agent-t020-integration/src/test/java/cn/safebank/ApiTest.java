package cn.safebank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:apitest;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@AutoConfigureMockMvc
class ApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Test void demoSessionCanReadOwnAccounts() throws Exception {
        var result=mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession();
        mvc.perform(get("/api/accounts").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").exists());
    }
    @Test void apiWithoutSessionIsRejected() throws Exception { mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized()); }
    @Test void csrfIsRequiredEvenInDemoMode() throws Exception {
        var r=mvc.perform(get("/api/session")).andReturn();var s=(MockHttpSession)r.getRequest().getSession();
        mvc.perform(post("/api/chat").session(s).contentType("application/json").content("{\"text\":\"查账单\"}")).andExpect(status().isForbidden());
    }
    @Test void offlineChatIsExplicitAndUsesRealTools() throws Exception {
        var r=mvc.perform(get("/api/session")).andReturn();var s=(MockHttpSession)r.getRequest().getSession();
        String csrf=json.readTree(r.getResponse().getContentAsString()).path("csrf").asText();
        mvc.perform(post("/api/chat").session(s).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"text\":\"分析2026-09账单\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("OFFLINE_DEMO")).andExpect(jsonPath("$.data.expense_minor").value(340300));
    }
    @Test void crossOriginMutationIsRejected() throws Exception {
        var r=mvc.perform(get("/api/session")).andReturn();var s=(MockHttpSession)r.getRequest().getSession();
        String csrf=json.readTree(r.getResponse().getContentAsString()).path("csrf").asText();
        mvc.perform(post("/api/chat").session(s).header("X-CSRF-Token",csrf).header("Origin","https://evil.example").contentType("application/json").content("{\"text\":\"查账单\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void fractionalMinorUnitsCannotBeSilentlyRounded() throws Exception {
        var r=mvc.perform(get("/api/session")).andReturn();var s=(MockHttpSession)r.getRequest().getSession();
        String csrf=json.readTree(r.getResponse().getContentAsString()).path("csrf").asText();
        mvc.perform(post("/api/transfers/preview").session(s).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"sourceId\":\"checking\",\"recipientId\":\"zhang-a\",\"amountMinor\":100.5}"))
            .andExpect(status().isBadRequest());
    }
}
