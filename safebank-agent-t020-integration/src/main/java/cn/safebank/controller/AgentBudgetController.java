package cn.safebank.controller;
import cn.safebank.dto.*;
import cn.safebank.service.AgentBudgetService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Profile("langgraph-agent") @RequestMapping("/internal/agent/model-budget")
public class AgentBudgetController {
 private final AgentBudgetService budget;public AgentBudgetController(AgentBudgetService budget){this.budget=budget;}
 @PostMapping("/reserve") public Map<String,Object> reserve(@RequestHeader("X-Agent-Grant") String token,@Valid @RequestBody AgentBudgetReserveDTO r){return budget.reserve(token,r);}
 @PostMapping("/record") public Map<String,Boolean> record(@RequestHeader("X-Agent-Grant") String token,@Valid @RequestBody AgentBudgetRecordDTO r){budget.record(token,r);return Map.of("recorded",true);}
}
