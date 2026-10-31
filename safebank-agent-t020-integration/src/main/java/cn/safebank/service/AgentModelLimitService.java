package cn.safebank.service;
import cn.safebank.dao.AgentModelLimitMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
@Service @Profile("langgraph-agent") public class AgentModelLimitService {
 private final AgentModelLimitMapper mapper;public AgentModelLimitService(AgentModelLimitMapper mapper){this.mapper=mapper;}
 public void save(String turn,Integer requested){if(mapper.insert(turn,Math.min(5,requested==null?5:requested))!=1)throw GatewayTurnService.denied();}
 public int limit(String turn){Integer n=mapper.limit(turn);if(n==null||n<1||n>5)throw GatewayTurnService.denied();return n;}
}
