package cn.safebank.model;

import cn.safebank.BankException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import static cn.safebank.model.ModelContracts.*;

@Component
public class DeepSeekModelClient implements ModelClient {
    private final ObjectMapper json;
    private final String base,key,model;
    private final Duration timeout;
    private final HttpClient http;
    private volatile boolean verified;
    private final DebugRequestBudget debugBudget;

    @Autowired
    public DeepSeekModelClient(ObjectMapper json,@Value("${bank.model.base-url}") String base,
            @Value("${bank.model.api-key}") String key,@Value("${bank.model.name}") String model,
            @Value("${bank.model.request-timeout-seconds:30}") int seconds,DebugRequestBudget debugBudget) {
        this(json,base,key,model,Duration.ofSeconds(seconds),debugBudget);
    }
    public DeepSeekModelClient(ObjectMapper json,String base,String key,String model,Duration timeout) {
        this(json,base,key,model,timeout,null);
    }
    public DeepSeekModelClient(ObjectMapper json,String base,String key,String model,Duration timeout,DebugRequestBudget debugBudget) {
        this.json=json;this.base=base;this.key=key;this.model=model;this.timeout=timeout;
        this.debugBudget=debugBudget;
        this.http=HttpClient.newBuilder().connectTimeout(timeout).build();
    }
    public String mode() { return key.isBlank()?"OFFLINE_DEMO":verified?"CONNECTION_VERIFIED":"MODEL_CONFIGURED"; }
    public String modelName() { return model; }
    public int availableRequests(){return debugBudget==null?Integer.MAX_VALUE:debugBudget.remaining();}
    public ModelReply complete(ModelRequest request) {
        if(key.isBlank())throw new BankException("MODEL_NOT_CONFIGURED","本机未配置模型密钥");
        String reservation=null;boolean success=false;JsonNode usage=null;
        try {
            var uri=URI.create(base.replaceAll("/+$","")+"/chat/completions");
            if(!"https".equals(uri.getScheme())&&!("http".equals(uri.getScheme())&&java.util.List.of("127.0.0.1","localhost").contains(uri.getHost())))
                throw new BankException("MODEL_CONFIGURATION_INVALID","模型地址必须使用HTTPS，或本地测试地址");
            var payload=Map.of("model",model,"messages",request.messages(),"tools",request.tools(),"stream",false,"max_tokens",2048,"thinking",Map.of("type","disabled"));
            var httpRequest=HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            reservation=debugBudget==null?null:debugBudget.reserve();
            var response=http.send(httpRequest,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) { verified=false;throw new BankException("MODEL_API_ERROR","模型服务返回HTTP "+response.statusCode()+"；未自动执行资金动作"); }
            JsonNode body;
            try { body=json.readTree(response.body()); } catch(Exception e) { throw new BankException("MODEL_RESPONSE_INVALID","模型响应不是有效JSON"); }
            JsonNode message=body.path("choices").path(0).path("message");
            if(!message.isObject())throw new BankException("MODEL_RESPONSE_INVALID","模型响应缺少消息");
            verified=true;
            success=true;usage=body.path("usage");
            return new ModelReply(message,body.path("usage"),FinishReason.fromProvider(body.path("choices").path(0).path("finish_reason")));
        } catch(BankException e) { verified=false;throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();verified=false;throw new BankException("MODEL_INTERRUPTED","模型请求中断"); }
        catch(Exception e) { verified=false;throw new BankException("MODEL_UNAVAILABLE","模型连接或响应失败，请检查本机配置"); }
        finally{if(reservation!=null)debugBudget.record(reservation,success,usage);}
    }
}

