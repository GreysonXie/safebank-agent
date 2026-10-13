package cn.safebank.controller;
import cn.safebank.BankException;
import cn.safebank.service.GatewayTurnService;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Profile("agent-gateway")
public class AgentGatewayStatusController {
    private final GatewayTurnService turns;
    public AgentGatewayStatusController(GatewayTurnService t){turns=t;}
    @GetMapping("/api/agent/turns/{id}") public Map<String,Object> status(HttpSession session,@PathVariable String id){var g=turns.read(id);if(g==null||!g.getUserId().equals(session.getAttribute("user")))throw new BankException("NOT_FOUND","轮次不存在或不属于当前会话");return Map.of("turnId",id,"state",g.getState(),"reconcileFailures",g.getReconcileFailures(),"outputVersion",g.getOutputVersion()==null?0:g.getOutputVersion(),"requiresReview",g.getState().equals("PAUSED"));}
}
