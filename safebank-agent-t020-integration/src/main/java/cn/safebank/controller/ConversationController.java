package cn.safebank.controller;
import cn.safebank.service.ContextService;
import cn.safebank.dto.ContextResetRequestDTO;
import cn.safebank.vo.ConversationVO;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/conversations")
public class ConversationController {
    private final ContextService contexts;
    public ConversationController(ContextService contexts){this.contexts=contexts;}
    @PostMapping public ConversationVO create(HttpSession session){String user=(String)session.getAttribute("user");return contexts.read(user,contexts.create(user));}
    @GetMapping("/{id}") public ConversationVO read(HttpSession session,@PathVariable String id){return contexts.read((String)session.getAttribute("user"),id);}
    @PostMapping("/{id}/context/reset") public ConversationVO reset(HttpSession session,@PathVariable String id,@Valid @RequestBody ContextResetRequestDTO request){var result=contexts.reset((String)session.getAttribute("user"),id,request.expectedContextVersion());for(String key:java.util.List.of("conversation","answerEvidence","mandatesContextSeen","lastContextResults","conversationUser"))session.removeAttribute(key);return result;}
}
