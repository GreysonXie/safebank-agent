package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.vo.AgentTurnStatusVO;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("agent-gateway")
public class AgentTurnStatusService {
 private final GatewayTurnService turns;
 public AgentTurnStatusService(GatewayTurnService turns){this.turns=turns;}
 public AgentTurnStatusVO status(String user,String id){
  if(user==null||id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw missing();
  var row=turns.read(id);
  if(row==null||!user.equals(row.getUserId()))throw missing();
  return new AgentTurnStatusVO(row.getTurnId(),row.getState(),row.getReconcileFailures(),row.getOutputVersion()==null?0:row.getOutputVersion(),"PAUSED".equals(row.getState()));
 }
 private static BankException missing(){return new BankException("NOT_FOUND","轮次不存在或不属于当前会话");}
}
