package cn.safebank.agent;
import cn.safebank.dto.AgentResultDTO;
import cn.safebank.entity.AgentGatewayTurnEntity;
import cn.safebank.vo.AgentAckVO;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
@Component @Profile("agent-gateway")
public class HttpAgentClient {
    private final URI base;private final String key;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<cn.safebank.service.AgentModelLimitService> limits;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HttpAgentClient(@Value("${bank.agent.base-url}") String base,@Value("${bank.agent.service-key}") String key,ObjectMapper json){this.base=URI.create(base);if(!this.base.getScheme().equals("http")||!this.base.getHost().equals("127.0.0.1")||this.base.getRawUserInfo()!=null||this.base.getRawQuery()!=null||this.base.getRawFragment()!=null||!(this.base.getPath().isEmpty()||this.base.getPath().equals("/")))throw new IllegalStateException("M2 permits only explicit loopback fake service");this.key=key;this.json=json;}
    public JsonNode start(AgentGatewayTurnEntity g,String token){var body=new java.util.LinkedHashMap<String,Object>(Map.of("userId",g.getUserId(),"conversationId",g.getConversationId(),"turnId",g.getTurnId(),"requestId",g.getRequestId(),"expectedVersion",g.getInputVersion(),"text",g.getUserText(),"grant",token));if(limits!=null&&limits.getIfAvailable()!=null)body.put("modelRequestLimit",limits.getObject().limit(g.getTurnId()));return request("POST","/internal/agent/turns",body);}
    public JsonNode get(String turnId){id(turnId);return request("GET","/internal/agent/turns/"+turnId,null);}
    /** 让 agent 侧终结真正占用该会话的、无结果的待核对 run；有界调用，失败由调用方如实上报。 */
    public JsonNode abandonBlocking(String userId,String conversationId,String turnId){return request("POST","/internal/agent/runs/abandon",Map.of("userId",userId,"conversationId",conversationId,"turnId",turnId),3);}
    public JsonNode recover(String turnId,String grant){id(turnId);return request("POST","/internal/agent/turns/"+turnId+"/recover",Map.of("turnId",turnId,"grant",grant));}
    public void ack(AgentAckVO a){id(a.turnId());var reply=request("POST","/internal/agent/turns/"+a.turnId()+"/ack",Map.of("turnId",a.turnId(),"outputVersion",a.outputVersion(),"resultHash",a.resultHash()));if(!java.util.Set.of("COMPLETED","NEEDS_CLARIFICATION").contains(reply.path("state").asText())||!reply.path("resultHash").asText().equals(a.resultHash())||reply.path("outputVersion").asLong()!=a.outputVersion())throw new AgentTransportException("ACK_MISMATCH");}
    public AgentResultDTO result(JsonNode reply,String turnId){if(!reply.path("state").asText().equals("RESULT_READY")||!reply.path("result").path("turnId").asText().equals(turnId))throw new AgentTransportException("RESULT_NOT_READY");try{return json.treeToValue(reply.get("result"),AgentResultDTO.class);}catch(Exception e){throw new AgentTransportException("INVALID_RESULT");}}
    public JsonNode modelSettings(){return settingsRequest("GET","/internal/agent/model-settings",null,5);}
    public JsonNode updateModelSettings(Map<String,Object> body){return settingsRequest("POST","/internal/agent/model-settings",body,5);}
    /** A provider probe can legitimately take close to a minute. */
    public JsonNode testModelSettings(Map<String,Object> body){return settingsRequest("POST","/internal/agent/model-settings/test",body,75);}
    private JsonNode request(String method,String path,Object body){return request(method,path,body,5);}
    private JsonNode request(String method,String path,Object body,int timeoutSeconds){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Agent HTTP must run outside bank transaction");
        try{
            var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization","Bearer "+key).header("Content-Type","application/json");
            if(method.equals("GET"))b.GET();else b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var pending=http.sendAsync(b.build(),info->new BoundedBodySubscriber());
            HttpResponse<byte[]> reply;
            try{reply=pending.get(timeoutSeconds,TimeUnit.SECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
            if(reply.statusCode()!=200)throw new AgentTransportException("AGENT_UNAVAILABLE");return json.readTree(reply.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AgentTransportException("AGENT_INTERRUPTED");}catch(AgentTransportException e){throw e;}catch(Exception e){throw new AgentTransportException("AGENT_UNAVAILABLE");}
    }
    /** Settings calls expose the agent's explicit rejection code to the browser. */
    private JsonNode settingsRequest(String method,String path,Object body,int timeoutSeconds){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Agent HTTP must run outside bank transaction");
        try{
            var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization","Bearer "+key).header("Content-Type","application/json");
            if(method.equals("GET"))b.GET();else b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var pending=http.sendAsync(b.build(),info->new BoundedBodySubscriber());
            HttpResponse<byte[]> reply;
            try{reply=pending.get(timeoutSeconds,TimeUnit.SECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
            if(reply.statusCode()==200)return json.readTree(reply.body());
            String code="MODEL_SETTINGS_REJECTED";
            try{var error=json.readTree(reply.body());if(error.hasNonNull("code"))code=error.get("code").asText();}catch(Exception ignored){}
            throw new cn.safebank.BankException(code,"模型设置未保存，请检查输入");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AgentTransportException("AGENT_INTERRUPTED");}catch(AgentTransportException|cn.safebank.BankException e){throw e;}catch(Exception e){throw new AgentTransportException("AGENT_UNAVAILABLE");}
    }
    private void id(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new AgentTransportException("INVALID_TURN");}
    /** Limits accumulation before allocation and lets the outer deadline cover the entire body. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completed=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return completed;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){int n=buffer.remaining();if(n>65536-bytes.size()){subscription.cancel();completed.completeExceptionally(new AgentTransportException("AGENT_RESPONSE_TOO_LARGE"));return;}byte[] block=new byte[n];buffer.get(block);bytes.writeBytes(block);}
            subscription.request(1);
        }
        public void onError(Throwable error){completed.completeExceptionally(error);}
        public void onComplete(){completed.complete(bytes.toByteArray());}
    }
    public static class AgentTransportException extends RuntimeException {public AgentTransportException(String code){super(code);}}
}
