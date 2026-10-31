package cn.safebank.service;
import cn.safebank.dao.AgentTurnReceiptMapper;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.InitializingBean;
@Component @org.springframework.context.annotation.Profile("!agent-gateway") @DependsOn("contextSchemaInitializer")
public class ContextRecoveryService implements InitializingBean {
    private final AgentTurnReceiptMapper turns;
    public ContextRecoveryService(AgentTurnReceiptMapper turns){this.turns=turns;}
    public void afterPropertiesSet(){turns.interruptProcessing();}
}
