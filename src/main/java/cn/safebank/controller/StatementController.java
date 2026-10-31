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
    /** 不指定月份时看当前业务月份（真实日期，Asia/Shanghai），不再固定某个历史月份。 */
    @GetMapping("/statement") public StatementVO statement(HttpSession s,@RequestParam(required=false) String month) {
        String selected=(month==null||month.isBlank())?java.time.YearMonth.from(cn.safebank.common.SimulationClock.businessDate(clock)).toString():month;
        return statements.statement(user(s),selected);
    }
    /** 年度账单：不指定年份时看当前业务年份；每月一行，口径与单月一致。 */
    @GetMapping("/statement/year") public YearStatementVO yearStatement(HttpSession s,@RequestParam(required=false) String year) {
        String selected=(year==null||year.isBlank())?String.valueOf(cn.safebank.common.SimulationClock.businessDate(clock).getYear()):year;
        return statements.yearStatement(user(s),selected);
    }
    /** 单月组合筛选（类别 + 收支方向 + 单笔金额下限），合计由服务端重算，与明细同源。 */
    @GetMapping("/statement/filter") public StatementFilterVO filter(HttpSession s,
        @RequestParam String month,@RequestParam(defaultValue="全部") String category,
        @RequestParam(defaultValue="支出") String direction,@RequestParam(defaultValue="0") long minAmountMinor) {
        return statements.filter(user(s),month,category,direction,minAmountMinor);
    }
    @GetMapping("/recurring") public List<RecurringPaymentVO> recurring(HttpSession s) {
        return statements.recurring(user(s));
    }
    @GetMapping("/statement/months") public List<String> months(HttpSession s) {
        return statements.months(user(s));
    }
    @GetMapping("/statement/years") public List<String> years(HttpSession s) {
        return statements.years(user(s));
    }
}
