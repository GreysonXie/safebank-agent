package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class ScheduleController {
    private final ScheduleService schedules;
    private final ActionService actions;
    public ScheduleController(ScheduleService schedules,ActionService actions) {
        this.schedules=schedules;
        this.actions=actions;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/schedules") public List<ScheduleVO> list(HttpSession s) {
        return schedules.list(user(s));
    }
    @PostMapping("/schedules/preview") public ActionVO preview(HttpSession s,@Valid @RequestBody SchedulePreviewRequestDTO r) {
        return actions.previewSchedule(user(s),r);
    }
    @PostMapping("/schedules/{id}/preview") public ActionVO manage(HttpSession s,@PathVariable String id,@Valid @RequestBody ScheduleManageRequestDTO r){return actions.previewScheduleManagement(user(s),id,r);}
    @GetMapping("/schedules/transfers") public List<ScheduleTransferVO> transfers(HttpSession s){return schedules.transfers(user(s));}
}
