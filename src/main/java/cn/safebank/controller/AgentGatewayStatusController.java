package cn.safebank.controller;
import cn.safebank.service.AgentTurnStatusService;
import cn.safebank.vo.AgentTurnStatusVO;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("agent-gateway")
public class AgentGatewayStatusController {
    private final AgentTurnStatusService status;
    public AgentGatewayStatusController(AgentTurnStatusService status){this.status=status;}
    @GetMapping("/api/agent/turns/{id}") public AgentTurnStatusVO status(HttpSession session,@PathVariable String id){return status.status((String)session.getAttribute("user"),id);}
}
