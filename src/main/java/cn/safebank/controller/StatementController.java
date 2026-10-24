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
    public StatementController(StatementService statements) {
        this.statements=statements;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/statement") public StatementVO statement(HttpSession s,@RequestParam(defaultValue="2026-09") String month) {
        return statements.statement(user(s),month);
    }
    @GetMapping("/recurring") public List<RecurringPaymentVO> recurring(HttpSession s) {
        return statements.recurring(user(s));
    }
    @GetMapping("/statement/months") public List<String> months(HttpSession s) {
        return statements.months(user(s));
    }
}
