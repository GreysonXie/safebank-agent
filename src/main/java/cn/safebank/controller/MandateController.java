package cn.safebank.controller;
import cn.safebank.AgentService;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api") public class MandateController {
    private final MandateService mandates;
    private final ActionService actions;
    public MandateController(MandateService mandates,ActionService actions) {
        this.mandates=mandates;
        this.actions=actions;
    }
    private String user(HttpSession s) {
        return (String)s.getAttribute("user");
    }
    @GetMapping("/mandates") public List<MandateVO> mandates(HttpSession s) {
        throw new cn.safebank.common.RiskRejectedException(new cn.safebank.common.RiskFailure("QUERY_CONFIRMATION_REQUIRED","读取本人银行代扣列表前需要确认查询范围",null,null,null));
    }
    @PostMapping("/mandates/preview") public ActionVO preview(HttpSession s,@Valid @RequestBody MandatePreviewRequestDTO r) {
        return actions.previewRevoke(user(s),r);
    }
}
