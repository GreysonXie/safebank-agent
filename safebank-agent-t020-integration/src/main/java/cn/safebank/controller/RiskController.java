package cn.safebank.controller;
import cn.safebank.BankException;
import cn.safebank.service.*;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api")
public class RiskController {
    private final AuthorizationGateService gate;private final VerificationService verification;private final RiskQueryService queries;private final RiskSummaryService summary;
    public RiskController(AuthorizationGateService gate,VerificationService verification,RiskQueryService queries,RiskSummaryService summary){this.gate=gate;this.verification=verification;this.queries=queries;this.summary=summary;}
    private String user(HttpSession s){return (String)s.getAttribute("user");}
    @GetMapping("/actions/{id}/risk") public RiskDecisionVO risk(HttpSession s,@PathVariable String id){return gate.riskView(user(s),id);}
    @PostMapping("/actions/{id}/risk/preview") public RiskDecisionVO refresh(HttpSession s,@PathVariable String id){return gate.refreshDecision(user(s),id,RiskChannel.HTTP);}
    @PostMapping("/actions/{id}/verification-challenges") public ChallengeVO challenge(HttpSession s,@PathVariable String id,@RequestBody ChallengeRequestDTO r){return RiskRejectedException.value(verification.create(user(s),id,r,RiskChannel.HTTP));}
    @PostMapping("/actions/{id}/verification-challenges/{challengeId}/verify") public VerificationProofVO verify(HttpSession s,@PathVariable String id,@PathVariable String challengeId,@RequestBody ChallengeVerifyRequestDTO r){return RiskRejectedException.value(verification.verify(user(s),id,challengeId,r));}
    @PostMapping("/query-intents") public ActionVO query(HttpSession s,@RequestBody QueryIntentRequestDTO r){if(!"QUERY_MANDATES".equals(r.operation()))throw new BankException("VALIDATION_ERROR","不支持的查询意图");return queries.prepareMandatesBound(user(s),RiskChannel.HTTP,r.conversationId(),r.expectedContextVersion());}
    @GetMapping("/risk/summary") public RiskSummaryVO summary(HttpSession s){return summary.view(user(s));}
}
