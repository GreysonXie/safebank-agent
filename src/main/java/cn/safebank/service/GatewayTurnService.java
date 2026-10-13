package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.ContextContracts.*;
import cn.safebank.dao.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.entity.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import static cn.safebank.service.ContextEvidenceService.require;

@Service @Profile("agent-gateway")
public class GatewayTurnService {
    private final ContextService contexts;
    private final AgentGatewayMapper mapper;
    private final AgentConversationMapper conversations;
    private final Clock clock;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired private AgentTurnReceiptMapper turnReceipts;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<AgentModelLimitService> limits;
    public GatewayTurnService(ContextService contexts,AgentGatewayMapper mapper,AgentConversationMapper conversations,Clock clock,PlatformTransactionManager manager){this.contexts=contexts;this.mapper=mapper;this.conversations=conversations;this.clock=clock;tx=new TransactionTemplate(manager);}
    public TurnStart begin(String user,ChatRequestDTO request,Instant sessionExpiry){
        if(!sessionExpiry.isAfter(clock.instant()))throw new BankException("SESSION_REQUIRED","原会话已过期");
        return tx.execute(status->{
            // Lock before ContextService's ownership pre-read establishes an RR snapshot.
            if(request.conversationId()!=null)conversations.selectOwnedForUpdate(user,request.conversationId());
            var start=contexts.begin(user,request);
            if(start.lease()!=null&&!mapper.pendingAck(user,start.lease().conversationId()).isEmpty())throw new BankException("AGENT_RECONCILING","上一轮正在核对完成确认，请等待后再开始新轮");
            if(start.lease()!=null){var l=start.lease();var g=new AgentGatewayTurnEntity();g.setTurnId(l.turnId());g.setConversationId(l.conversationId());g.setUserId(user);g.setRequestId(l.requestId());g.setUserText(l.userText());g.setInputVersion(l.inputVersion());g.setSessionExpiresAt(sessionExpiry.atOffset(ZoneOffset.UTC));g.setCreatedAt(now());g.setNextCheckAt(now().plusSeconds(30));g.setState("PENDING");g.setReconcileFailures(0);require(mapper.insertGateway(g));}
            if(start.lease()!=null&&limits.getIfAvailable()!=null)limits.getObject().save(start.lease().turnId(),request.modelRequestLimit());
            return start;
        });
    }
    /** Caller holds a short transaction. Always conversation then original turn. */
    public AgentGatewayTurnEntity lock(String turnId,boolean requireLive){
        var g=mapper.gateway(turnId);if(g==null)throw denied();
        var c=conversations.selectOwnedForUpdate(g.getUserId(),g.getConversationId());
        var t=mapper.lockTurn(g.getUserId(),turnId);
        if(c==null||t==null||!t.getConversationId().equals(g.getConversationId())||!t.getInputVersion().equals(g.getInputVersion()))throw denied();
        g=mapper.lockGateway(turnId);
        if(requireLive&&(!t.getState().equals("PROCESSING")||!c.getVersion().equals(g.getInputVersion())||!g.getSessionExpiresAt().toInstant().isAfter(clock.instant())||!c.getExpiresAt().toInstant().isAfter(clock.instant())||c.getState().equals("EXPIRED")))throw denied();
        return g;
    }
    public TurnLease lease(AgentGatewayTurnEntity g){return new TurnLease(g.getUserId(),g.getConversationId(),g.getTurnId(),g.getInputVersion(),g.getRequestId(),g.getUserText());}
    public AgentGatewayTurnEntity read(String turnId){return mapper.gateway(turnId);}
    public AgentGatewayTurnEntity original(String user,String cid,String requestId){var receipt=turnReceipts.selectRequest(user,cid,requestId);if(receipt==null)throw denied();var g=mapper.gateway(receipt.getId());if(g==null||!g.getUserId().equals(user)||!g.getConversationId().equals(cid))throw denied();return g;}
    public OffsetDateTime now(){return clock.instant().atOffset(ZoneOffset.UTC);}
    public static BankException denied(){return new BankException("PERMISSION_DENIED","原轮次、版本或短期能力已失效");}
}
