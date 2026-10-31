package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class AuditController {
    private final AuditService audit;
    public AuditController(AuditService audit) {
        this.audit=audit;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/audit") public List<AuditEventVO> list(HttpSession s) {
        return audit.list(user(s));
    }
}
