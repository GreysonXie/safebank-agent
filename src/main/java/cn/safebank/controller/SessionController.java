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
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.core.env.Environment environment;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<ModelModeResolver> modelModes;
    public SessionController(AgentService agent,SimulationService simulation) {
        this.agent=agent;
        this.simulation=simulation;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/session") public SessionVO session(HttpSession s) {
        if(s.getAttribute("user")==null) {
            s.setAttribute("user","u1");
            s.setAttribute("csrf",UUID.randomUUID().toString());
        }
        boolean fake=environment.matchesProfiles("agent-gateway");
        if(environment.matchesProfiles("langgraph-agent")){
            var resolver=modelModes.getIfAvailable();
            String mode=resolver!=null?resolver.mode():environment.getProperty("bank.agent.model-mode","mock").equals("deepseek")?"LANGGRAPH_MODEL":"LANGGRAPH_MOCK";
            String model=resolver!=null?resolver.modelName():environment.getProperty("bank.agent.model-name","deepseek-v4-flash");
            return new SessionVO("合成用户 / u1",(String)s.getAttribute("csrf"),mode,model,"LOCAL_DEMO_ONLY",simulation.enabled());
        }
        return new SessionVO("合成用户 / u1",(String)s.getAttribute("csrf"),fake?"FAKE_AGENT":agent.mode(),fake?"fake-python-protocol":agent.modelName(),"LOCAL_DEMO_ONLY",simulation.enabled());
    }
}
