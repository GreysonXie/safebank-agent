package cn.safebank.controller;
import cn.safebank.service.InternalModelService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Profile("langgraph-agent") @RequestMapping("/internal/agent/model")
public class InternalModelController {
    private final InternalModelService models;
    public InternalModelController(InternalModelService models){this.models=models;}
    @PostMapping("/configuration") public Map<String,Object> configuration(@RequestHeader("X-Agent-Grant") String token){return models.configuration(token);}
    @PostMapping("/complete") public JsonNode complete(@RequestHeader("X-Agent-Grant") String token,@RequestBody InternalModelService.Request request){return models.complete(token,request);}
}
