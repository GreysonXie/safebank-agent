package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class SimulationController {
    private final SimulationService simulation;
    private final ScheduleService schedules;
    public SimulationController(SimulationService simulation,ScheduleService schedules) {
        this.simulation=simulation;
        this.schedules=schedules;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @PostMapping("/simulation/faults") public SimulationConfiguredVO configure(HttpSession s,@Valid @RequestBody SimulationFaultRequestDTO r) {
        simulation.configure(user(s),r.actionId(),r.submitMode(),r.lookupMode());
        return new SimulationConfiguredVO(true);
    }
    @PostMapping("/simulation/run-due") public List<ActionVO> run(HttpSession s,@Valid @RequestBody SimulationRunRequestDTO r) {
        return schedules.runDue(user(s),r.date());
    }
}
