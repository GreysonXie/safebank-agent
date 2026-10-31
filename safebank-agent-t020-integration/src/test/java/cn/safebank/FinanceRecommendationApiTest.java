package cn.safebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:finance-api;DB_CLOSE_DELAY=-1", "bank.model.api-key="})
@AutoConfigureMockMvc
class FinanceRecommendationApiTest extends BankTestFixture {
    private static final String REQUEST = """
            {"riskPreference":"CONSERVATIVE","purpose":"PLANNED","liquidity":"FLEXIBLE",
             "amountMinor":1000000,"horizonMonths":12,"capitalProtection":false}
            """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void requiresSessionCsrfAndSameOrigin() throws Exception {
        mvc.perform(post("/api/finance/recommendations").contentType("application/json").content(REQUEST))
                .andExpect(status().isUnauthorized());
        var session = session();
        mvc.perform(post("/api/finance/recommendations").session(session).contentType("application/json").content(REQUEST))
                .andExpect(status().isForbidden());
        mvc.perform(postRecommendation(session, REQUEST).header("Origin", "https://example.invalid"))
                .andExpect(status().isForbidden());
    }

    @Test void returnsTypedPreferencesSourceAndCompleteEligibilityEvidence() throws Exception {
        mvc.perform(postRecommendation(session(), REQUEST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEMO"))
                .andExpect(jsonPath("$.source").value("SafeBank 本地合成产品库"))
                .andExpect(jsonPath("$.preferences.amountMinor").value(1000000))
                .andExpect(jsonPath("$.preferences.horizonMonths").value(12))
                .andExpect(jsonPath("$.candidates", hasSize(4)))
                .andExpect(jsonPath("$.exclusions", hasSize(3)))
                .andExpect(jsonPath("$.evaluatedCount").value(7))
                .andExpect(jsonPath("$.candidates[0].product.id").value("DEMO-CASH"))
                .andExpect(jsonPath("$.candidates[0].product.periodReturnBps").value(nullValue()))
                .andExpect(jsonPath("$.candidates[0].reasons", not(empty())))
                .andExpect(jsonPath("$.asOf").value("2026-09-30"))
                .andExpect(jsonPath("$.periodStart").value("2025-09-30"))
                .andExpect(jsonPath("$.periodEnd").value("2026-09-30"));
    }

    @ParameterizedTest
    @ValueSource(strings={"{}", "null", "{", "{\"riskPreference\":\"UNKNOWN\"}"})
    void malformedOrMissingPreferencesHaveStableClientError(String body) throws Exception {
        mvc.perform(postRecommendation(session(), body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREFERENCES"))
                .andExpect(jsonPath("$.message", not(emptyString())));
    }

    @ParameterizedTest
    @ValueSource(strings={"amountMinor:0", "amountMinor:10000000001", "horizonMonths:0", "horizonMonths:121",
            "amountMinor:100.5", "horizonMonths:1.5"})
    void outOfRangeAndFractionalValuesHaveStableClientError(String example) throws Exception {
        var body = json.readTree(REQUEST);
        var parts = example.split(":");
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).set(parts[0], json.readTree(parts[1]));
        mvc.perform(postRecommendation(session(), body.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREFERENCES"));
    }

    @Test void repeatedRecommendationsLeaveBalancesAndBankingRecordsUnchanged() throws Exception {
        var session = session();
        var before = bankingSnapshot();
        for (int repeat = 0; repeat < 3; repeat++) {
            mvc.perform(postRecommendation(session, REQUEST)).andExpect(status().isOk());
        }
        assertEquals(before, bankingSnapshot());
    }

    private MockHttpSession session() throws Exception {
        return (MockHttpSession) mvc.perform(get("/api/session")).andExpect(status().isOk())
                .andReturn().getRequest().getSession();
    }

    private MockHttpServletRequestBuilder postRecommendation(MockHttpSession session, String body) {
        return post("/api/finance/recommendations").session(session)
                .header("X-CSRF-Token", session.getAttribute("csrf"))
                .contentType("application/json").content(body);
    }

    private Map<String, List<Map<String, Object>>> bankingSnapshot() {
        var snapshot = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("accounts", "mandates", "actions", "ledger", "transactions", "schedules", "workflows", "audit")) {
            snapshot.put(table, db.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return snapshot;
    }
}
