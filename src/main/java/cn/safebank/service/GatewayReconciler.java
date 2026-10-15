package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.agent.HttpAgentClient;
import cn.safebank.dao.AgentGatewayMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @Profile("agent-gateway") @EnableScheduling
@ConditionalOnProperty(name="bank.agent.reconcile.enabled",havingValue="true",matchIfMissing=true)
public class GatewayReconciler {
    private final AgentGatewayMapper mapper;private final GatewayTurnService turns;private final GatewayResultService results;private final HttpAgentClient client;
    @org.springframework.beans.factory.annotation.Autowired private AgentCapabilityService capabilities;
    public GatewayReconciler(AgentGatewayMapper m,GatewayTurnService t,GatewayResultService r,HttpAgentClient c){mapper=m;turns=t;results=r;client=c;}
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void scan(){for(var g:mapper.due(turns.now()))reconcile(g.getTurnId());}
    public void reconcile(String id){
        try{var reply=client.get(id);String state=reply.path("state").asText();
            var g=mapper.gateway(id);
            if(g==null)return;
            if(state.equals("NO_RUN")){if("PAUSED".equals(g.getState()))results.pause(id);else results.noRun(id);return;}
            if(state.equals("RESULT_READY")){var ack=results.complete(client.result(reply,id));client.ack(ack);results.acknowledged(id,ack.resultHash(),ack.outputVersion());}
            else if((state.equals("COMPLETED")||state.equals("NEEDS_CLARIFICATION"))&&g.getOutputVersion()!=null){var ack=results.saved(id);if(!reply.path("resultHash").asText().equals(ack.resultHash())||reply.path("outputVersion").asLong()!=ack.outputVersion())throw new BankException("CONFLICT","远端 ACK 不一致");results.acknowledged(id,ack.resultHash(),ack.outputVersion());}
            else if(state.equals("RECOVERY_REQUIRED")){
                if("PAUSED".equals(g.getState()))results.pause(id);
                else{client.recover(id,capabilities.issue(id));results.deferred(id,false);}
            }
            else if(state.equals("PROCESSING"))results.deferred(id,false);else results.pause(id);
        }catch(HttpAgentClient.AgentTransportException e){results.deferred(id,true);}catch(BankException e){if(e.code.equals("PERMISSION_DENIED"))results.interrupted(id);else results.pause(id);}
    }
}
