package cn.safebank.service;
import cn.safebank.dto.*;
import cn.safebank.model.DebugRequestBudget;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
@Service @Profile("langgraph-agent")
public class AgentBudgetService {
 private final AgentCapabilityService capabilities;private final DebugRequestBudget budget;private final TransactionTemplate tx;
 @org.springframework.beans.factory.annotation.Autowired private AgentModelLimitService limits;
 public AgentBudgetService(AgentCapabilityService c,DebugRequestBudget b,PlatformTransactionManager manager){capabilities=c;budget=b;tx=new TransactionTemplate(manager);}
 public Map<String,Object> reserve(String token,AgentBudgetReserveDTO r){return tx.execute(s->{var g=capabilities.authorize(token,true);if(!g.getTurnId().equals(r.javaTurnId()))throw GatewayTurnService.denied();String id=budget.reserveEffect(r.operationKey(),r.inputHash(),r.javaTurnId(),r.purpose(),limits.limit(r.javaTurnId()));return Map.of("reservationId",id,"remaining",budget.remaining());});}
 public void record(String token,AgentBudgetRecordDTO r){tx.executeWithoutResult(s->{var g=capabilities.originalIdentity(token);if(!g.getTurnId().equals(r.javaTurnId()))throw GatewayTurnService.denied();budget.recordEffect(r.reservationId(),r.operationKey(),r.inputHash(),r.javaTurnId(),r.purpose(),r.success(),r.usage());});}
}
