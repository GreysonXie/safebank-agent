package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service @Profile("langgraph-agent")
public class InternalModelService {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record Request(String revision,JsonNode payload,String reservationId,String operationKey,String inputHash,String javaTurnId,String purpose) {}
    private final ModelConfigurationService settings;private final CompatibleModelHttp http;private final AgentCapabilityService capabilities;private final DebugRequestBudget usage;private final TransactionTemplate tx;
    public InternalModelService(ModelConfigurationService s,CompatibleModelHttp h,AgentCapabilityService c,DebugRequestBudget u,PlatformTransactionManager manager){settings=s;http=h;capabilities=c;usage=u;tx=new TransactionTemplate(manager);}
    public Map<String,Object> configuration(String token){
        var user=tx.execute(t->capabilities.authorize(token,true).getUserId());var s=settings.settings(user);
        return Map.of("managed","local".equals(s.source()),"enabled",s.enabled(),"model",s.model(),"revision",s.revision());
    }
    public JsonNode complete(String token,Request request){
        var identity=tx.execute(t->capabilities.authorize(token,true));
        if(!identity.getTurnId().equals(request.javaTurnId()))throw GatewayTurnService.denied();
        var connection=settings.connection(identity.getUserId());
        if(!connection.configured()||!connection.revision().equals(request.revision()))throw new BankException("MODEL_CONFIGURATION_CHANGED","模型配置已变化，本轮已停止发送，请开始新一轮对话");
        var payload=request.payload();if(payload==null||!payload.path("messages").isArray()||payload.path("messages").isEmpty()||payload.path("messages").size()>40)throw new BankException("VALIDATION_ERROR","模型消息格式无效");
        usage.claimEffect(request.reservationId(),request.operationKey(),request.inputHash(),request.javaTurnId(),request.purpose());
        return http.request(connection,"/chat/completions",http.body(connection,payload.path("messages"),List.of(),Math.max(1,Math.min(2048,payload.path("max_tokens").asInt(2048))),true));
    }
}
