package cn.safebank.controller;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import cn.safebank.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.Map;
@RestController @Profile("agent-gateway") @RequestMapping("/internal/agent")
public class InternalAgentController {
    private final AgentCapabilityService capabilities;private final GatewayResultService results;
    public InternalAgentController(AgentCapabilityService c,GatewayResultService r){capabilities=c;results=r;}
    @PostMapping("/tools") public AgentToolReceiptVO call(@RequestHeader("X-Agent-Grant") String token,@Valid @RequestBody AgentToolRequestDTO request){return capabilities.call(token,request);}
    @PostMapping("/answers/validate") public Map<String,Object> validate(@RequestHeader("X-Agent-Grant") String token,@Valid @RequestBody AgentResultDTO result){return results.validate(token,result);}
    public record Renewal(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=80) String turnId) {}
    @PostMapping("/grants/renew") public Map<String,String> renew(@RequestHeader("X-Agent-Grant") String token,@Valid @RequestBody Renewal request){return Map.of("grant",capabilities.renew(request.turnId(),token));}
}
