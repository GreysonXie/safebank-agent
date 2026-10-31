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
    /** 侧栏跟随对话口径：该会话最近一次通用账单查询的回执快照（只读，不发起新查询）。 */
    @GetMapping("/api/conversations/{conversationId}/statement-query") public java.util.Map<String,Object> statementQuery(HttpSession session,@PathVariable String conversationId){return status.statementQuery((String)session.getAttribute("user"),conversationId);}
    /** 对话驱动的卡片预览：取该会话最近一次卡片预览回执，让卡片页直接打开待确认预览（只读，不确认）。 */
    @GetMapping("/api/conversations/{conversationId}/card-operation") public java.util.Map<String,Object> cardOperation(HttpSession session,@PathVariable String conversationId){return status.cardOperation((String)session.getAttribute("user"),conversationId);}
    @PostMapping("/api/conversations/{conversationId}/turns/{requestId}/close") public java.util.Map<String,Object> close(HttpSession session,@PathVariable String conversationId,@PathVariable String requestId){return status.close((String)session.getAttribute("user"),conversationId,requestId);}
}
