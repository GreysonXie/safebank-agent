package cn.safebank.controller;
import cn.safebank.BankException;
import cn.safebank.agent.HttpAgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** Local-only proxy so the web page can switch model mode without a restart. */
@RestController @Profile("langgraph-agent")
public class ModelSettingsController {
    private static final Set<String> FIELDS=Set.of("mode","apiKey","modelName","provider","baseUrl");
    private final ObjectProvider<HttpAgentClient> client;
    public ModelSettingsController(ObjectProvider<HttpAgentClient> client){this.client=client;}
    private HttpAgentClient require(){
        var agent=client.getIfAvailable();
        if(agent==null)throw new BankException("AGENT_UNAVAILABLE","模型服务未启用，无法修改模型设置");
        return agent;
    }
    private Map<String,Object> body(JsonNode body){
        if(body==null||!body.isObject())throw new BankException("INVALID_BODY","请求体格式不正确");
        var values=new LinkedHashMap<String,Object>();
        var names=new ArrayList<String>();body.fieldNames().forEachRemaining(names::add);
        for(String name:names){
            if(!FIELDS.contains(name))throw new BankException("INVALID_BODY","不支持的字段 "+name);
            var value=body.get(name);
            if(!value.isTextual())throw new BankException("INVALID_BODY",name+" 必须是文本");
            values.put(name,value.asText());
        }
        return values;
    }
    @GetMapping("/api/model-settings") public JsonNode settings(){return require().modelSettings();}
    @PostMapping("/api/model-settings") public JsonNode save(@RequestBody JsonNode body){return require().updateModelSettings(body(body));}
    @PostMapping("/api/model-settings/test") public JsonNode test(@RequestBody JsonNode body){return require().testModelSettings(body(body));}
}
