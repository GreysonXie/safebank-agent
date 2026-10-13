package cn.safebank.agent;
import cn.safebank.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.service.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
@Service @Profile("agent-gateway")
public class GatewayCoordinator {
    private final GatewayTurnService turns;private final AgentCapabilityService capabilities;private final GatewayResultService results;private final HttpAgentClient client;private final ContextService contexts;
    public GatewayCoordinator(GatewayTurnService t,AgentCapabilityService c,GatewayResultService r,HttpAgentClient h,ContextService s){turns=t;capabilities=c;results=r;client=h;contexts=s;}
    public Map<String,Object> chat(String user,ChatRequestDTO r,HttpSession session){
        String cid=r.conversationId();if(cid==null){synchronized(session){if(!user.equals(session.getAttribute("contextUser"))){session.removeAttribute("activeConversationId");session.setAttribute("contextUser",user);}cid=(String)session.getAttribute("activeConversationId");if(cid==null){cid=contexts.create(user);session.setAttribute("activeConversationId",cid);}}}
        var expiry=Instant.ofEpochMilli(session.getLastAccessedTime()).plusSeconds(session.getMaxInactiveInterval());
        var start=turns.begin(user,new ChatRequestDTO(r.text(),r.modelRequestLimit(),cid,r.requestId(),r.expectedContextVersion()),expiry);
        if(start.replayResponse()!=null)return start.replayResponse();
        if(start.processing()){
            var g=turns.original(user,cid,r.requestId());
            try{return finish(g.getTurnId(),client.get(g.getTurnId()));}catch(HttpAgentClient.AgentTransportException e){results.deferred(g.getTurnId(),true);throw new BankException("AGENT_RECONCILING","正在核对原请求，未重新发送模型请求");}
        }
        String turnId=start.lease().turnId();
        try{
            String token=capabilities.issue(turnId);var reply=client.start(turns.read(turnId),token);
            return finish(turnId,reply);
        }catch(HttpAgentClient.AgentTransportException e){results.deferred(turnId,true);throw new BankException("AGENT_RECONCILING","服务响应未确定，保留原轮次等待核对，未重发");}
    }
    private Map<String,Object> finish(String turnId,com.fasterxml.jackson.databind.JsonNode reply){
        String state=reply.path("state").asText();
        if(state.equals("RECOVERY_REQUIRED")){client.recover(turnId,capabilities.issue(turnId));results.deferred(turnId,false);throw new BankException("CONTEXT_PROCESSING","正在恢复原轮次，请稍候");}
        if(!state.equals("RESULT_READY")){if(state.equals("PROCESSING")){results.deferred(turnId,false);throw new BankException("CONTEXT_PROCESSING","原轮次仍在处理，未重复发送");}results.pause(turnId);throw new BankException("AGENT_REVIEW_REQUIRED","原轮次状态需要核对，已暂停自动处理");}
        var ack=results.complete(client.result(reply,turnId));try{client.ack(ack);results.acknowledged(turnId,ack.resultHash(),ack.outputVersion());}catch(HttpAgentClient.AgentTransportException e){results.deferred(turnId,true);}return ack.response();
    }
}
