package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/workflows") public class WorkflowController {
    private final WorkflowService workflows;
    private final WorkflowPlanViewService plans;
    public WorkflowController(WorkflowService workflows,WorkflowPlanViewService plans) {
        this.workflows=workflows;
        this.plans=plans;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @PostMapping public WorkflowVO create(HttpSession s,@Valid @RequestBody WorkflowRequestDTO r) {
        return workflows.create(user(s),r);
    }
    @GetMapping public List<WorkflowVO> list(HttpSession s) {
        return workflows.list(user(s));
    }
    @GetMapping("/{id}") public WorkflowVO get(HttpSession s,@PathVariable UUID id) {
        return workflows.get(user(s),id);
    }
    @GetMapping("/{id}/plan") public TaskPlanVO plan(HttpSession s,@PathVariable UUID id) {
        return plans.get(user(s),id);
    }
    @PostMapping("/{id}/resume") public WorkflowVO resume(HttpSession s,@PathVariable UUID id,@Valid @RequestBody WorkflowVersionRequestDTO r) {
        return workflows.resume(user(s),id,r.version());
    }
    @PostMapping("/{id}/revise") public WorkflowVO revise(HttpSession s,@PathVariable UUID id,@Valid @RequestBody WorkflowRevisionRequestDTO r) {
        return workflows.revise(user(s),id,r.version(),r.request());
    }
    @PostMapping("/{id}/cancel") public WorkflowVO cancel(HttpSession s,@PathVariable UUID id,@Valid @RequestBody WorkflowVersionRequestDTO r) {
        return workflows.cancel(user(s),id,r.version());
    }
}
