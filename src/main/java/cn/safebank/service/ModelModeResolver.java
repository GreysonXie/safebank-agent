package cn.safebank.service;
import cn.safebank.agent.HttpAgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
/** Current model selection, read from the agent service at request time. */
@Service @Profile("langgraph-agent")
public class ModelModeResolver {
    private final ObjectProvider<HttpAgentClient> client;
    private final Environment environment;
    public ModelModeResolver(ObjectProvider<HttpAgentClient> client,Environment environment){this.client=client;this.environment=environment;}
    private JsonNode settings(){
        var agent=client.getIfAvailable();
        if(agent==null)return null;
        try{return agent.modelSettings();}catch(Exception e){return null;}
    }
    public String mode(){
        var settings=settings();
        if(settings!=null)return "deepseek".equals(settings.path("mode").asText())?"LANGGRAPH_MODEL":"LANGGRAPH_MOCK";
        return environment.getProperty("bank.agent.model-mode","mock").equals("deepseek")?"LANGGRAPH_MODEL":"LANGGRAPH_MOCK";
    }
    public String modelName(){
        var settings=settings();
        if(settings!=null&&settings.hasNonNull("modelName"))return settings.path("modelName").asText();
        return environment.getProperty("bank.agent.model-name","deepseek-flash");
    }
}
