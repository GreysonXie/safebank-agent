package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class ChatController {
    private final cn.safebank.agent.ContextAgentCoordinator agent;
    public ChatController(cn.safebank.agent.ContextAgentCoordinator agent) {
        this.agent=agent;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @PostMapping("/chat") public Map<String,Object> chat(HttpSession s,@Valid @RequestBody ChatRequestDTO r) {
        return agent.chat(user(s),r,s);
    }
}
