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
    /** 原请求恢复面板：读取原轮次状态，必要时受控结束（不会自动重发）。 */
    @GetMapping("/api/conversations/{conversationId}/turns/{requestId}") public java.util.Map<String,Object> resolve(HttpSession session,@PathVariable String conversationId,@PathVariable String requestId){return status.resolve((String)session.getAttribute("user"),conversationId,requestId);}
    @PostMapping("/api/conversations/{conversationId}/turns/{requestId}/close") public java.util.Map<String,Object> close(HttpSession session,@PathVariable String conversationId,@PathVariable String requestId){return status.close((String)session.getAttribute("user"),conversationId,requestId);}
}
