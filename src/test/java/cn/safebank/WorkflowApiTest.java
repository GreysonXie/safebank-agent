package cn.safebank;
import static cn.safebank.WorkflowTestContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflowapi;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(TestClockConfiguration.class)
class WorkflowApiTest extends BankTestFixture {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired WorkflowTestSupport workflows;
    @Test void workflowMutationRequiresCsrfAndConflictingVersionReturns409() throws Exception {
        var session=(MockHttpSession)mvc.perform(get("/api/session")).andReturn().getRequest().getSession();
        var r=new WorkflowRequest("2026-09",List.of("music"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
        mvc.perform(post("/api/workflows").session(session).contentType("application/json").content(json.writeValueAsString(r))).andExpect(status().isForbidden());
        var response=mvc.perform(post("/api/workflows").session(session).header("X-CSRF-Token",session.getAttribute("csrf")).contentType("application/json").content(json.writeValueAsString(r))).andExpect(status().isOk()).andExpect(jsonPath("$.pendingAction.status").value("AWAITING_AUTH")).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(post("/api/workflows/"+id+"/resume").session(session).header("X-CSRF-Token",session.getAttribute("csrf")).contentType("application/json").content("{\"version\":99}")).andExpect(status().isConflict());
        mvc.perform(get("/api/workflows").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/workflows/"+id+"/plan").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.workflowId").value(id)).andExpect(jsonPath("$.nodes.length()").value(5))
            .andExpect(jsonPath("$.nodes[2].displayState").value("AWAITING_AUTH"))
            .andExpect(jsonPath("$.nodes[3].displayState").value("WAITING_DEPENDENCY"));
    }
    @Test void invalidWorkflowHasNoActionAndOtherSessionCannotReadIt() throws Exception {
        var s=(MockHttpSession)mvc.perform(get("/api/session")).andReturn().getRequest().getSession();
        mvc.perform(post("/api/workflows").session(s).header("X-CSRF-Token",s.getAttribute("csrf")).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        var r=new WorkflowRequest("2026-09",List.of("music"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);var w=workflows.create("u1",r);
        s.setAttribute("user","u2");mvc.perform(get("/api/workflows/"+w.id()).session(s)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/workflows/"+w.id()+"/plan").session(s)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
