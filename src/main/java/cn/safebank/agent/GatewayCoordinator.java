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
        var lease=start.lease();
        try{
            String token=capabilities.issue(turnId);var reply=client.start(turns.read(turnId),token);
            return finish(turnId,reply);
        }catch(HttpAgentClient.AgentTransportException e){results.deferred(turnId,true,"AGENT_TRANSPORT_UNAVAILABLE:start");throw new BankException("AGENT_RECONCILING","服务响应未确定，保留原轮次等待核对，未重发");}
        catch(RuntimeException error){
            // 任何失败都必须把两条轮次置为终态：否则这一轮永远停在"处理中"，该会话之后每次提问都会被拒。
            String code=error instanceof BankException bank?bank.code:"AGENT_FAILED";
            try{results.failed(turnId,code);}catch(RuntimeException ignored){}
            try{if(lease!=null)contexts.fail(lease,code);}catch(RuntimeException ignored){}
            throw error;
        }
    }
    private Map<String,Object> finish(String turnId,com.fasterxml.jackson.databind.JsonNode reply){
        String state=reply.path("state").asText();
        if("INTERRUPTED".equals(turns.read(turnId).getState()))throw new BankException("AGENT_TURN_CLOSED","本轮已结束，未取得答案；可以直接提出新问题");
        if(state.equals("FAILED")){
            // 本轮失败但银行侧没有任何写操作：把真实原因告诉客户并结束该轮，不用"暂停"占住会话。
            String code=reply.path("errorCode").asText("AGENT_FAILED");
            results.failed(turnId,code);
            throw new BankException(code,failureMessage(code));
        }
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
    /** 失败终结口径：本轮已确定失败且没有任何银行写操作，直接说明原因并让客户继续提问。 */
    static String failureMessage(String code){
        if("MODEL_REQUEST_REJECTED_401".equals(code)||"MODEL_REQUEST_REJECTED_403".equals(code))
            return "真实模型服务拒绝了本次请求（"+code.substring("MODEL_REQUEST_REJECTED_".length())+" 鉴权失败）：当前保存的 API Key 无效或已失效。请到「模型管理」重新填写有效的 API Key 并点「检测连接」，本轮没有执行任何操作";
        if(code!=null&&code.startsWith("MODEL_REQUEST_REJECTED_"))
            return "真实模型服务拒绝了请求（"+code.substring("MODEL_REQUEST_REJECTED_".length())+"），常见原因是模型名不可用或请求参数不合法；本轮没有执行任何操作";
        if("EFFECT_FAILED".equals(code)||"BANK_CALLBACK_REJECTED".equals(code))
            return "这次查询没有成功（可能是网络或服务暂时不可用），没有执行任何操作；可以直接换个说法再问，比如说明月份或具体项目";
        if("MODEL_EFFECT_UNKNOWN".equals(code)||"MODEL_SEND_UNCERTAIN".equals(code))
            return "本次没有拿到模型响应（可能是模型密钥/额度/模型名配置有误，或网络中断），未执行任何银行操作；可到「模型管理」点「检测连接」核对配置后再问，如已有任务或银行操作以原回执为准";
        if("ANSWER_REPAIR_EXHAUSTED".equals(code)||"ANSWER_REPAIR_BUDGET_EXHAUSTED".equals(code))
            return "这个问题没能给出通过事实校验的回答（已重写仍不合格），所以没有交付，也没有执行任何操作；可以指定月份或换一种问法再试";
        if("EFFECT_CONFLICT".equals(code))
            return "本轮与历史记录的快照不一致，已按未完成处理，没有执行任何操作；可以直接重新提问";
        if("MODEL_REQUEST_BUDGET_EXCEEDED".equals(code)||"MODEL_TURN_BUDGET_EXCEEDED".equals(code))
            return "模型调用次数达到本机上限，未发送任何请求；可在模型设置中确认配置后重试";
        if(code!=null&&code.startsWith("MODEL_"))
            return "本次未取得回答（"+code+"），未执行任何操作；可直接重试或检查模型设置";
        return "本次未取得回答（"+(code==null||code.isBlank()?"AGENT_FAILED":code)+"），未执行任何操作；可以直接重新提问";
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
