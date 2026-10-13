package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class AccountController {
    private final AccountService accounts;
    private final RecipientService recipients;
    public AccountController(AccountService accounts,RecipientService recipients) {
        this.accounts=accounts;
        this.recipients=recipients;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/accounts") public List<AccountVO> accounts(HttpSession s) {
        return accounts.list(user(s));
    }
    @GetMapping("/recipients") public List<RecipientVO> recipients(HttpSession s,@RequestParam String query) {
        return recipients.search(user(s),query);
    }
}
