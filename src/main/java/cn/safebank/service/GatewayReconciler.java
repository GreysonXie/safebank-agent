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
            if(state.equals("NO_RUN")){if("PAUSED".equals(g.getState()))results.pause(id,"AGENT_RUN_MISSING_ALREADY_PAUSED");else results.noRun(id,"AGENT_RUN_MISSING");return;}
            if(state.equals("RESULT_READY")){var ack=results.complete(client.result(reply,id));client.ack(ack);results.acknowledged(id,ack.resultHash(),ack.outputVersion());}
            else if(state.equals("FAILED")){String code=reply.path("errorCode").asText("AGENT_FAILED");results.failed(id,code);}
            else if((state.equals("COMPLETED")||state.equals("NEEDS_CLARIFICATION"))&&g.getOutputVersion()!=null){var ack=results.saved(id);if(!reply.path("resultHash").asText().equals(ack.resultHash())||reply.path("outputVersion").asLong()!=ack.outputVersion())throw new BankException("CONFLICT","远端 ACK 不一致");results.acknowledged(id,ack.resultHash(),ack.outputVersion());}
            else if(state.equals("RECOVERY_REQUIRED")){
                if("PAUSED".equals(g.getState()))results.pause(id,"AGENT_RECOVERY_REQUIRED");
                else{client.recover(id,capabilities.issue(id));results.deferred(id,false,"AGENT_RECOVERY_REQUESTED");}
            }
            else if(state.equals("PROCESSING"))results.deferred(id,false,"AGENT_TURN_PROCESSING");
            else{String code=reply.path("errorCode").asText("");results.pause(id,"AGENT_RESULT_"+(code.isBlank()?"UNKNOWN":code));}
        }catch(HttpAgentClient.AgentTransportException e){results.deferred(id,true,"AGENT_TRANSPORT_UNAVAILABLE:reconcile");}
        catch(BankException e){if(e.code.equals("PERMISSION_DENIED"))results.interrupted(id);else results.pause(id,"AGENT_RECONCILE_"+e.code);}
    }
}
