package cn.safebank.controller;
import cn.safebank.service.AgentTurnStatusService;
import cn.safebank.vo.AgentTurnStatusVO;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Profile("agent-gateway")
public class AgentGatewayStatusController {
    private final AgentTurnStatusService status;
    public AgentGatewayStatusController(AgentTurnStatusService status){this.status=status;}
    @GetMapping("/api/agent/turns/{id}") public AgentTurnStatusVO status(HttpSession session,@PathVariable String id){return status.status((String)session.getAttribute("user"),id);}
    /** 按 conversationId+requestId 解析原轮次（当前会话用户），只读。 */
    @GetMapping("/api/agent/requests/{requestId}") public Map<String,Object> resolve(HttpSession session,@PathVariable String requestId,@RequestParam String conversationId){return status.resolve((String)session.getAttribute("user"),conversationId,requestId);}
    /** 安全结束一个已暂停且无已提交答案的原轮次；不碰银行动作，不清库。 */
    @PostMapping("/api/agent/requests/{requestId}/close") public Map<String,Object> close(HttpSession session,@PathVariable String requestId,@RequestBody Map<String,String> body){return status.close((String)session.getAttribute("user"),body.get("conversationId"),requestId);}
}
