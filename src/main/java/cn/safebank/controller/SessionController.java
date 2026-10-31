package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class SessionController {
    private final AgentService agent;
    private final SimulationService simulation;
    private final java.time.Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.core.env.Environment environment;
    @org.springframework.beans.factory.annotation.Autowired private ModelConfigurationService modelSettings;
    public SessionController(AgentService agent,SimulationService simulation,java.time.Clock clock) {
        this.agent=agent;
        this.simulation=simulation;
        this.clock=clock;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/session") public SessionVO session(HttpSession s) {
        if(s.getAttribute("user")==null) {
            s.setAttribute("user","u1");
            s.setAttribute("csrf",UUID.randomUUID().toString());
        }
        String businessDate=cn.safebank.common.SimulationClock.businessDate(clock).toString();
        boolean fake=environment.matchesProfiles("agent-gateway");
        if(environment.matchesProfiles("langgraph-agent")){
            var config=modelSettings.settings(user(s));boolean online="local".equals(config.source())?config.enabled()&&config.hasApiKey():Set.of("deepseek","openai").contains(environment.getProperty("bank.agent.model-mode","mock"));
            return new SessionVO("合成用户 / u1",(String)s.getAttribute("csrf"),online?"LANGGRAPH_MODEL":"LANGGRAPH_MOCK",config.model(),"LOCAL_DEMO_ONLY",simulation.enabled(),businessDate);
        }
        return new SessionVO("合成用户 / u1",(String)s.getAttribute("csrf"),fake?"FAKE_AGENT":agent.mode(),fake?"fake-python-protocol":agent.modelName(),"LOCAL_DEMO_ONLY",simulation.enabled(),businessDate);
    }
}
