package cn.safebank.agent;
import cn.safebank.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.service.*;
import cn.safebank.common.ContextContracts.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class ContextAgentCoordinator {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<GatewayCoordinator> gateway;
    private final ContextService contexts;private final ContextAgentSupport support;private final AgentService agent;private final ClarificationService clarification;
    public ContextAgentCoordinator(ContextService c,ContextAgentSupport s,AgentService a,ClarificationService v){contexts=c;support=s;agent=a;clarification=v;}
    public Map<String,Object> chat(String user,ChatRequestDTO request,HttpSession session){
        if(gateway!=null&&gateway.getIfAvailable()!=null)return gateway.getObject().chat(user,request,session);
        String conversationId=request.conversationId();
        if(conversationId==null){synchronized(session){if(!Objects.equals(user,session.getAttribute("contextUser"))){session.removeAttribute("activeConversationId");session.setAttribute("contextUser",user);}conversationId=(String)session.getAttribute("activeConversationId");if(conversationId==null){conversationId=contexts.create(user);session.setAttribute("activeConversationId",conversationId);}}}
        var effective=new ChatRequestDTO(request.text(),request.modelRequestLimit(),conversationId,request.requestId(),request.expectedContextVersion());var start=contexts.begin(user,effective);
        if(start.replayResponse()!=null)return start.replayResponse();if(start.processing())throw new BankException("CONTEXT_PROCESSING","原请求仍在处理，未重复调用模型或银行");
        var lease=start.lease();
        try{
            support.open(lease,contexts.snapshot(user,conversationId),request.text());
            var response=new LinkedHashMap<>(agent.chatContext(user,request.text(),session,request.modelRequestLimit()==null?5:Math.min(5,request.modelRequestLimit()),support));
            response.put("clarification",clarification.describe(support.snapshot()));
            contexts.complete(lease,new ContextCommit(support.snapshot(),List.of(),response));return contexts.completedResponse(lease);
        }catch(Exception e){contexts.fail(lease,e instanceof BankException b?b.code:"CONTEXT_FAILED");throw e;}finally{support.close();}
    }
}
