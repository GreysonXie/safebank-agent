package cn.safebank.model;

import cn.safebank.BankException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import static cn.safebank.model.ModelContracts.*;

@Component
public class DeepSeekModelClient implements ModelClient {
    private final ObjectMapper json;
    private final String base,key,model;
    private final Duration timeout;
    private final CompatibleModelHttp transport;
    private volatile boolean verified;
    private final DebugRequestBudget debugBudget;
    @Autowired(required=false) private cn.safebank.service.ModelConfigurationService settings;
    private volatile String verifiedRevision;

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
        this.transport=new CompatibleModelHttp(json);
    }
    private ModelConnection connection(String user){return settings==null?new ModelConnection(base.contains("deepseek")||model.startsWith("deepseek")?"deepseek":base.contains("api.openai.com")?"openai":"custom",base,key,model,!key.isBlank(),"legacy"):settings.connection(user);}
    public String mode() { var c=connection("u1");return !c.configured()?"OFFLINE_DEMO":verified&&c.revision().equals(verifiedRevision)?"CONNECTION_VERIFIED":"MODEL_CONFIGURED"; }
    public String modelName() { return connection("u1").model(); }
    public int availableRequests(){return debugBudget==null?Integer.MAX_VALUE:debugBudget.remaining();}
    public ModelReply complete(ModelRequest request) {
        var connection=connection(request.userId());
        if(!connection.configured())throw new BankException("MODEL_NOT_CONFIGURED","本机未配置模型密钥");
        String reservation=null;boolean success=false;JsonNode usage=null;
        try {
            var payload=transport.body(connection,request.messages(),request.tools(),2048,false);
            reservation=debugBudget==null?null:debugBudget.reserve();
            JsonNode body=transport.request(connection,"/chat/completions",payload,timeout);
            JsonNode message=body.path("choices").path(0).path("message");
            if(!message.isObject())throw new BankException("MODEL_RESPONSE_INVALID","模型响应缺少消息");
            verified=true;
            verifiedRevision=connection.revision();
            success=true;usage=body.path("usage");
            return new ModelReply(message,body.path("usage"),FinishReason.fromProvider(body.path("choices").path(0).path("finish_reason")));
        } catch(BankException e) { verified=false;throw e; }
        catch(Exception e) { verified=false;throw new BankException("MODEL_UNAVAILABLE","模型连接或响应失败，请检查本机配置"); }
        finally{if(reservation!=null)debugBudget.record(reservation,success,usage);}
    }
}

