package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class StatementController {
    private final StatementService statements;
    private final java.time.Clock clock;
    public StatementController(StatementService statements,java.time.Clock clock) {
        this.statements=statements;
        this.clock=clock;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    /** 不指定月份时按月看"当前业务月份"，不再固定某个历史月份。 */
    @GetMapping("/statement") public StatementVO statement(HttpSession s,@RequestParam(required=false) String month) {
        String selected=(month==null||month.isBlank())?java.time.YearMonth.from(cn.safebank.common.SimulationClock.businessDate(clock)).toString():month;
        return statements.statement(user(s),selected);
    }
    @GetMapping("/recurring") public List<RecurringPaymentVO> recurring(HttpSession s) {
        return statements.recurring(user(s));
    }
}
