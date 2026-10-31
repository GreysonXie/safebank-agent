package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.*;
import cn.safebank.entity.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.vo.ConversationVO;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import static cn.safebank.service.ContextEvidenceService.require;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
@Service public class ContextService {
    private final AgentConversationMapper conversations;
    private final AgentTurnReceiptMapper turns;
    private final AgentContextSlotMapper slots;
    private final ContextEvidenceService evidence;
    private final ObjectMapper json;
    private final Clock clock;private final org.springframework.beans.factory.ObjectProvider<AgentMandateReceiptService> agentReceipts;
    private final TransactionTemplate tx;
    public ContextService(AgentConversationMapper c,AgentTurnReceiptMapper t,AgentContextSlotMapper s,ContextEvidenceService e,ObjectMapper j,Clock clock,PlatformTransactionManager manager,org.springframework.beans.factory.ObjectProvider<AgentMandateReceiptService> agentReceipts){conversations=c;turns=t;slots=s;evidence=e;json=j;this.clock=clock;this.agentReceipts=agentReceipts;tx=new TransactionTemplate(manager);}
    public String create(String user){return tx.execute(status->{var c=new AgentConversationEntity();c.setId(UUID.randomUUID().toString());c.setUserId(user);c.setVersion(1L);c.setIntent("GENERAL");c.setState("ACTIVE");c.setDisplayHistoryJson("[]");c.setCreatedAt(now());c.setUpdatedAt(now());c.setExpiresAt(now().plusDays(7));require(conversations.insert(c));return c.getId();});}
    public TurnStart begin(String user,ChatRequestDTO request){
        String id=request.conversationId()==null?create(user):request.conversationId();owned(user,id,false);
        return tx.execute(status->{
            var c=owned(user,id,true);String rid=request.requestId()==null?UUID.randomUUID().toString():request.requestId();
            String hash=ContextEvidenceService.hash(request.text()+"\n"+Objects.toString(request.modelRequestLimit(),"default"));var old=turns.selectRequest(user,id,rid);
            if(old!=null){
                if(!old.getRequestHash().equals(hash))throw conflict("相同请求编号对应不同内容");
                if(old.getState().equals("COMPLETED"))return new TurnStart(null,map(old.getResponseJson()),false);
                if(old.getState().equals("PROCESSING"))return new TurnStart(null,null,true);
                throw new BankException("CONTEXT_INTERRUPTED","原请求未完成，请先核对关联动作，不自动重放");
            }
            if(request.expectedContextVersion()!=null&&request.expectedContextVersion()!=c.getVersion())throw conflict("对话版本已改变，请读取最新上下文");
            if(turns.selectByConversation(user,id).stream().anyMatch(t->t.getState().equals("PROCESSING")))throw conflict("上一轮尚在处理中，请等待或查看状态");
            var t=new AgentTurnReceiptEntity();t.setId(UUID.randomUUID().toString());t.setConversationId(id);t.setUserId(user);t.setRequestId(rid);t.setRequestHash(hash);t.setInputVersion(c.getVersion());t.setState("PROCESSING");t.setCreatedAt(now());require(turns.insert(t));
            return new TurnStart(new TurnLease(user,id,t.getId(),c.getVersion(),rid,request.text()),null,false);
        });
    }
    public ContextSnapshot snapshot(String user,String id){var c=owned(user,id,false);var values=new EnumMap<SlotName,SlotValue>(SlotName.class);for(var s:slots.selectByConversation(user,id))values.put(SlotName.from(s.getSlotName()),new SlotValue(tree(s.getValueJson()),SlotProvenance.valueOf(s.getProvenance()),s.getSourceTurnId(),s.getEvidenceId()));return new ContextSnapshot(id,c.getVersion(),Intent.valueOf(c.getIntent()),ConversationState.valueOf(c.getState()),Map.copyOf(values),tree(c.getProposalJson()),evidence.load(user,id),history(c.getDisplayHistoryJson()));}
    public ConversationVO read(String user,String id){var v=view(snapshot(user,id));var refs=new ArrayList<BankReference>();for(var t:turns.selectByConversation(user,id))if(t.getBankReferencesJson()!=null)try{refs.addAll(json.readValue(t.getBankReferencesJson(),new TypeReference<List<BankReference>>(){}));}catch(Exception e){throw new BankException("CONFLICT","原银行对象关联无法读取");}return new ConversationVO(v.conversationId(),v.version(),v.intent(),v.state(),v.slots(),v.proposal(),v.displayHistory(),List.copyOf(refs));}
    public static ConversationVO view(ContextSnapshot s){var values=new LinkedHashMap<String,JsonNode>();s.slots().forEach((k,v)->values.put(k.key(),v.value()));return new ConversationVO(s.conversationId(),s.version(),s.intent().name(),s.state().name(),values,s.proposal(),s.displayHistory());}
    public ConversationVO complete(TurnLease lease,ContextCommit commit){
        return tx.execute(status->{
            var c=owned(lease.userId(),lease.conversationId(),true);var t=turn(lease);if(!t.getState().equals("PROCESSING")||c.getVersion()!=lease.inputVersion())throw conflict("对话处理版本冲突");
            var s=commit.snapshot();if(!s.conversationId().equals(c.getId())||s.version()!=c.getVersion())throw conflict("上下文不属于当前轮次");
            long v=c.getVersion()+1;c.setVersion(v);c.setIntent(s.intent().name());c.setState(s.state().name());c.setProposalJson(s.proposal()==null?null:s.proposal().toString());c.setUpdatedAt(now());c.setExpiresAt(now().plusDays(7));
            var display=new ArrayList<>(s.displayHistory());display.add(Map.of("user",lease.userText(),"assistant",Objects.toString(commit.response().get("text"),"")));while(display.size()>4)display.removeFirst();c.setDisplayHistoryJson(encode(display));
            require(conversations.updateIfVersion(lease.userId(),c.getId(),lease.inputVersion(),c));
            slots.deleteOwned(lease.userId(),c.getId());for(var entry:s.slots().entrySet()){
                var value=entry.getValue();var row=new AgentContextSlotEntity();row.setConversationId(c.getId());row.setUserId(lease.userId());row.setSlotName(entry.getKey().key());row.setValueJson(value.value().toString());row.setProvenance(value.provenance().name());row.setContextVersion(v);row.setSourceTurnId(value.sourceTurnId());row.setEvidenceId(value.evidenceId());row.setUpdatedAt(now());require(slots.insert(row));
            }
            evidence.store(lease,commit.evidence());var result=read(lease.userId(),c.getId());var response=new LinkedHashMap<>(commit.response());response.put("conversationId",c.getId());response.put("contextVersion",v);response.put("context",result);t.setResponseJson(encode(response));t.setOutputVersion(v);t.setState("COMPLETED");t.setCompletedAt(now());require(turns.updateOwned(t));return result;
        });
    }
    public Map<String,Object> completedResponse(TurnLease lease){var t=turn(lease);return map(t.getResponseJson());}
    public void fail(TurnLease lease,String code){tx.executeWithoutResult(status->{var t=turn(lease);if(t.getState().equals("PROCESSING")){t.setState("FAILED");t.setErrorCode(code);t.setCompletedAt(now());require(turns.updateOwned(t));}});}
    public void interrupt(TurnLease lease){tx.executeWithoutResult(status->{var c=conversations.selectOwnedForUpdate(lease.userId(),lease.conversationId());if(c==null)throw conflict("轮次归属无效");var t=turns.selectRequestForUpdate(lease.userId(),lease.conversationId(),lease.requestId());if(t==null||!t.getId().equals(lease.turnId()))throw conflict("轮次归属无效");if(t.getState().equals("PROCESSING")){t.setState("INTERRUPTED");t.setErrorCode("CONTEXT_INTERRUPTED");t.setCompletedAt(now());require(turns.updateOwned(t));}});}
    public ConversationVO reset(String user,String id,long expected){owned(user,id,false);return tx.execute(status->{var c=owned(user,id,true);if(c.getVersion()!=expected||turns.selectByConversation(user,id).stream().anyMatch(t->t.getState().equals("PROCESSING")))throw conflict("对话版本冲突或仍在处理中");slots.deleteOwned(user,id);evidence.clear(user,id);agentReceipts.ifAvailable(r->r.invalidateConversation(user,id));c.setVersion(expected+1);c.setIntent("GENERAL");c.setState("ACTIVE");c.setProposalJson(null);c.setDisplayHistoryJson("[]");c.setUpdatedAt(now());c.setExpiresAt(now().plusDays(7));require(conversations.updateIfVersion(user,id,expected,c));return read(user,id);});}
    public AgentTurnReceiptEntity turn(TurnLease lease){var t=turns.selectRequest(lease.userId(),lease.conversationId(),lease.requestId());if(t==null||!t.getId().equals(lease.turnId()))throw conflict("轮次归属无效");return t;}
    private AgentConversationEntity owned(String user,String id,boolean lock){
        var c=lock?conversations.selectOwnedForUpdate(user,id):conversations.selectOwned(user,id);if(c==null)throw new BankException("NOT_FOUND","对话不存在或不属于当前用户");
        if(c.getState().equals("EXPIRED")||!c.getExpiresAt().toInstant().isAfter(clock.instant())){
            // Outside model execution; a separate committed cleanup precedes the visible expiry error.
            if(!c.getState().equals("EXPIRED"))expire(user,id);
            throw new BankException("CONTEXT_EXPIRED","对话上下文已过期，请新建对话；已有银行动作仍可按原编号核对");
        }
        return c;
    }
    private void expire(String user,String id){tx.executeWithoutResult(status->{var c=conversations.selectOwnedForUpdate(user,id);slots.deleteOwned(user,id);evidence.clear(user,id);agentReceipts.ifAvailable(r->r.invalidateConversation(user,id));for(var t:turns.selectByConversation(user,id)){t.setResponseJson(null);t.setToolIntentsJson(null);require(turns.updateOwned(t));}long old=c.getVersion();c.setVersion(old+1);c.setState("EXPIRED");c.setProposalJson(null);c.setDisplayHistoryJson(null);require(conversations.updateIfVersion(user,id,old,c));});}
    private OffsetDateTime now(){return clock.instant().atOffset(ZoneOffset.UTC);}
    private JsonNode tree(String text){if(text==null)return null;try{return json.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> map(String text){if(text==null)return Map.of();try{return json.readValue(text,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private List<Map<String,Object>> history(String text){if(text==null)return List.of();try{return json.readValue(text,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static BankException conflict(String text){return new BankException("CONFLICT",text);}
}
