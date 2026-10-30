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
        if(start.replayResponse()!=null){
            reviewSavedReceipt(turns.original(user,cid,r.requestId()));
            return start.replayResponse();
        }
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
        if("INTERRUPTED".equals(turns.read(turnId).getState()))throw new BankException("AGENT_TURN_CLOSED","本轮已结束，未取得答案；请继续提出新问题");
        if(state.equals("FAILED")){
            String code=reply.path("errorCode").asText("AGENT_FAILED");
            results.interrupted(turnId);
            throw new BankException(code,failureMessage(code));
        }
        if(state.equals("RECOVERY_REQUIRED")){
            if("PAUSED".equals(turns.read(turnId).getState())){
                results.pause(turnId);
                throw new BankException("AGENT_REVIEW_REQUIRED","原轮次已暂停，尚无可核验的完整结果；继续保留原请求，不自动恢复或重发");
            }
            client.recover(turnId,capabilities.issue(turnId));results.deferred(turnId,false);throw new BankException("CONTEXT_PROCESSING","正在恢复原轮次，请稍候");
        }
        if(!state.equals("RESULT_READY")){if(state.equals("PROCESSING")){results.deferred(turnId,false);throw new BankException("CONTEXT_PROCESSING","原轮次仍在处理，未重复发送");}results.pause(turnId);throw new BankException("AGENT_REVIEW_REQUIRED",reviewMessage(reply.path("errorCode").asText()));}
        var ack=results.complete(client.result(reply,turnId));try{client.ack(ack);results.acknowledged(turnId,ack.resultHash(),ack.outputVersion());}catch(HttpAgentClient.AgentTransportException e){results.deferred(turnId,true);}return ack.response();
    }
    /** Reconcile only the receipt of an already committed reply; never restart its run. */
    private void reviewSavedReceipt(cn.safebank.entity.AgentGatewayTurnEntity original){
        if(!java.util.Set.of("PAUSED","ACK_PENDING").contains(original.getState()))return;
        String id=original.getTurnId();
        try{
            var ack=results.saved(id);
            var reply=client.get(id);
            String state=reply.path("state").asText();
            if(state.equals("RESULT_READY")){
                var remote=client.result(reply,id);
                if(!results.resultHash(remote).equals(ack.resultHash())){results.pause(id);return;}
                client.ack(ack);
            }else if(!java.util.Set.of("COMPLETED","NEEDS_CLARIFICATION").contains(state)
                    ||!reply.path("resultHash").asText().equals(ack.resultHash())
                    ||!reply.path("outputVersion").isIntegralNumber()
                    ||reply.path("outputVersion").asLong()!=ack.outputVersion()){
                results.pause(id);return;
            }
            results.acknowledged(id,ack.resultHash(),ack.outputVersion());
        }catch(HttpAgentClient.AgentTransportException|BankException error){results.pause(id);}
    }
    static String reviewMessage(String code){
        if(code!=null&&code.matches("MODEL_REQUEST_REJECTED_(400|401|403|404|405|413|415|422)"))
            return "模型服务明确拒绝请求，请检查模型名称、请求参数与账户配置；原轮次已保留，不会自动重发";
        if("EFFECT_FAILED".equals(code)||"BANK_CALLBACK_REJECTED".equals(code))
            return "这次查询没有成功（可能是网络或服务暂时不可用），未执行任何操作；可以直接换个说法再问，比如说明产品和月份";
        if("HISTORY_QUERY_REQUIRED".equals(code))
            return "我没能确定要查哪款产品的历史表现；请直接说产品名称或编号加月份，例如“固收90天持有 9 月的历史表现”；没有执行任何操作";
        if("MODEL_EFFECT_UNKNOWN".equals(code))
            return "模型响应结果尚未确定，原轮次已保留，请核对原请求，不要重复提交";
        return "原轮次状态需要核对，已暂停自动处理";
    }
    /** 预发送阶段就失败的确定性错误：把真实原因告诉客户，不再当作“需要核对”。 */
    static String failureMessage(String code){
        if(code!=null&&code.startsWith("MODEL_REQUEST_REJECTED_"))
            return "真实模型服务拒绝了请求（"+code.substring("MODEL_REQUEST_REJECTED_".length())+"），常见原因是 API Key 无效、账户额度不足或模型名不可用；本轮没有执行任何操作";
        if("EFFECT_FAILED".equals(code)||"BANK_CALLBACK_REJECTED".equals(code))
            return "这次查询没有成功（可能是网络或服务暂时不可用），未执行任何操作；可以直接换个说法再问，比如说明产品和月份";
        if("HISTORY_QUERY_REQUIRED".equals(code))
            return "我没能确定要查哪款产品的历史表现；请直接说产品名称或编号加月份，例如“固收90天持有 9 月的历史表现”；没有执行任何操作";
        if("MODEL_EFFECT_UNKNOWN".equals(code))
            return "本次调用未取得确定结果，请核对原请求；如已有生成的任务或银行操作，以原回执为准，确认状态前请勿重复办理";
        if("ANSWER_REPAIR_EXHAUSTED".equals(code)||"ANSWER_REPAIR_BUDGET_EXHAUSTED".equals(code))
            return "这个问题我没能给出通过事实校验的回答（已连续重写仍未通过），所以没有交付，也没有执行任何操作；可以换个问法、指定月份或具体项目再试";
        if("MODEL_REQUEST_BUDGET_EXCEEDED".equals(code)||"MODEL_TURN_BUDGET_EXCEEDED".equals(code))
            return "模型调用次数达到本机上限，未发送任何请求；可在部署配置中调整上限后重试";
        if(code!=null&&code.startsWith("MODEL_"))
            return "本次未取得回答（"+code+"），未执行任何操作；可直接重试或检查模型设置";
        return "本次未取得回答（"+code+"），未执行任何操作";
    }
}
