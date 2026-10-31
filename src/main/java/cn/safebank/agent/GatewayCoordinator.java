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
            try{return finish(g.getTurnId(),client.get(g.getTurnId()));}catch(HttpAgentClient.AgentTransportException e){results.deferred(g.getTurnId(),true,"AGENT_TRANSPORT_UNAVAILABLE:get");throw new BankException("AGENT_RECONCILING","正在核对原请求，未重新发送模型请求");}
        }
        String turnId=start.lease().turnId();
        try{
            String token=capabilities.issue(turnId);var reply=client.start(turns.read(turnId),token);
            return finish(turnId,reply);
        }catch(HttpAgentClient.AgentTransportException e){results.deferred(turnId,true,"AGENT_TRANSPORT_UNAVAILABLE:start");throw new BankException("AGENT_RECONCILING","服务响应未确定，保留原轮次等待核对，未重发");}
    }
    private Map<String,Object> finish(String turnId,com.fasterxml.jackson.databind.JsonNode reply){
        String state=reply.path("state").asText();
        if(state.equals("RECOVERY_REQUIRED")){
            if("PAUSED".equals(turns.read(turnId).getState())){
                results.pause(turnId,"AGENT_RECOVERY_REQUIRED");
                throw new BankException("AGENT_REVIEW_REQUIRED","原轮次已暂停，尚无可核验的完整结果；继续保留原请求，不自动恢复或重发。失败原因已写入审计事件（AGENT_PAUSED）");
            }
            client.recover(turnId,capabilities.issue(turnId));results.deferred(turnId,false,"AGENT_RECOVERY_REQUESTED");throw new BankException("CONTEXT_PROCESSING","正在恢复原轮次，请稍候");
        }
        if(!state.equals("RESULT_READY")){if(state.equals("PROCESSING")){results.deferred(turnId,false,"AGENT_TURN_PROCESSING");throw new BankException("CONTEXT_PROCESSING","原轮次仍在处理，未重复发送");}
            String code=reply.path("errorCode").asText("");results.pause(turnId,"AGENT_RESULT_"+(code.isBlank()?"UNKNOWN":code));throw new BankException("AGENT_REVIEW_REQUIRED",reviewMessage(code));}
        var ack=results.complete(client.result(reply,turnId));try{client.ack(ack);results.acknowledged(turnId,ack.resultHash(),ack.outputVersion());}catch(HttpAgentClient.AgentTransportException e){results.deferred(turnId,true,"AGENT_TRANSPORT_UNAVAILABLE:ack");}return ack.response();
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
                if(!results.resultHash(remote).equals(ack.resultHash())){results.pause(id,"AGENT_RESULT_HASH_MISMATCH");return;}
                client.ack(ack);
            }else if(!java.util.Set.of("COMPLETED","NEEDS_CLARIFICATION").contains(state)
                    ||!reply.path("resultHash").asText().equals(ack.resultHash())
                    ||!reply.path("outputVersion").isIntegralNumber()
                    ||reply.path("outputVersion").asLong()!=ack.outputVersion()){
                results.pause(id,"AGENT_RECEIPT_STATE_"+state);return;
            }
            results.acknowledged(id,ack.resultHash(),ack.outputVersion());
        }catch(HttpAgentClient.AgentTransportException error){results.pause(id,"AGENT_RECEIPT_TRANSPORT_UNAVAILABLE");}
        catch(BankException error){results.pause(id,"AGENT_RECEIPT_REVIEW_FAILED:"+error.code);}
    }
    /** 让失败原因可见：已知原因给业务口径说明，未知原因附真实错误码，不笼统写“需要核对”。 */
    static String reviewMessage(String code){
        if("MODEL_REQUEST_BUDGET_EXCEEDED".equals(code))
            return "该历史轮次曾触发旧版累计限制；当前项目已取消累计上限。请核对原轮次后新建对话，不会自动重发";
        if("MODEL_TURN_BUDGET_EXCEEDED".equals(code))
            return "本轮模型请求次数已达上限，已停止调用；原轮次已保留，请核对已有查询和操作预览";
        if("MODEL_DEBUG_BUDGET_INVALID".equals(code))
            return "项目模型用量记录无法读取或写入，已停止调用；请检查本机用量文件，勿删除历史记录";
        if(code!=null&&code.matches("MODEL_REQUEST_REJECTED_(400|401|403|404|405|413|415|422)"))
            return "模型服务明确拒绝请求，请检查模型名称、请求参数与账户配置；原轮次已保留，不会自动重发";
        if("MODEL_EFFECT_UNKNOWN".equals(code))
            return "模型响应结果尚未确定，原轮次已保留，请核对原请求，不要重复提交";
        if("ANSWER_REPAIR_EXHAUSTED".equals(code)||"ANSWER_REPAIR_BUDGET_EXHAUSTED".equals(code))
            return "本轮未能生成经过核对的回答，已安全结束；没有执行任何资金操作，可换一种问法或核对原记录后重试";
        if("BANK_CALLBACK_REJECTED".equals(code))
            return "银行侧明确拒绝了本次查询或预览请求；没有执行资金操作，请核对参数后重试";
        if("AGENT_BUSY".equals(code))
            return "助手正忙（并发已满），本轮未执行；请稍后重新提问";
        if("ACK_CONFLICT".equals(code)||"NOT_RECOVERABLE".equals(code)||"INVALID_RECOVERY".equals(code))
            return "原轮次的结果版本与本地记录不一致，已暂停自动处理；请核对原请求记录，勿重复提交";
        if("INVALID_BODY".equals(code)||"INCOMPLETE_BODY".equals(code)||"BODY_LIMIT".equals(code)||"INVALID_REQUEST".equals(code))
            return "本轮请求未被助手接受（内容不符合协议），没有执行任何操作；请重新提问";
        if(code!=null&&code.matches("[A-Z][A-Z0-9_]{2,63}"))
            return "原轮次已暂停：助手返回 "+code+"，没有执行资金操作。可查询原记录状态或换一种问法重试";
        return "原轮次状态需要核对，已暂停自动处理";
    }
}
