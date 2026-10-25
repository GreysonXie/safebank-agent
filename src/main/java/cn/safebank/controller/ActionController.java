package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class ActionController {
    private final ActionService actions;
    private final ActionExecutionService execution;
    public ActionController(ActionService actions,ActionExecutionService execution) {
        this.actions=actions;
        this.execution=execution;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @PostMapping("/transfers/preview") public ActionVO preview(HttpSession s,@Valid @RequestBody TransferPreviewRequestDTO r) {
        return actions.previewTransfer(user(s),r);
    }
    @GetMapping("/actions") public List<ActionVO> list(HttpSession s){return actions.list(user(s));}
    @PostMapping("/actions/{id}/authorize") public ActionVO authorize(HttpSession s,@PathVariable String id,@Valid @RequestBody AuthorizationRequestDTO r) {
        return actions.authorize(user(s),id,r);
    }
    @PostMapping("/actions/{id}/submit") public ActionExecutionResultVO submit(HttpSession s,@PathVariable String id) {
        return execution.submit(user(s),id);
    }
    @PostMapping("/actions/{id}/cancel") public ActionVO cancel(HttpSession s,@PathVariable String id) {
        return actions.cancel(user(s),id);
    }
    @GetMapping("/actions/{id}") public ActionExecutionResultVO status(HttpSession s,@PathVariable String id) {
        return execution.observe(user(s),id);
    }
}
