package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.*;
import cn.safebank.entity.AgentMandateQueryReceiptEntity;
import cn.safebank.vo.MandateVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.safebank.service.ContextEvidenceService.*;
@Service @Profile("agent-gateway")
public class AgentMandateReceiptService {
 private final AgentMandateQueryReceiptMapper receipts;private final AgentConversationMapper conversations;private final AgentTurnReceiptMapper turns;private final ActionMapper actions;private final ObjectMapper json;private final Clock clock;
 public AgentMandateReceiptService(AgentMandateQueryReceiptMapper r,AgentConversationMapper c,AgentTurnReceiptMapper t,ActionMapper a,ObjectMapper j,Clock clock){receipts=r;conversations=c;turns=t;actions=a;json=j;this.clock=clock;}
 /** Caller holds the user risk guard before taking the conversation lock. */
 public void validateBinding(String user,String cid,Long expected){
  var c=conversations.selectOwnedForUpdate(user,cid);if(c==null)throw new BankException("NOT_FOUND","对话不存在或不属于当前用户");
  if(c.getState().equals("EXPIRED")||!c.getExpiresAt().toInstant().isAfter(clock.instant()))throw new BankException("CONTEXT_EXPIRED","对话上下文已过期，请新建对话");
  if(expected==null||expected.longValue()!=c.getVersion()||turns.selectByConversation(user,cid).stream().anyMatch(t->t.getState().equals("PROCESSING")))throw new BankException("CONFLICT","对话版本已改变或仍在处理中，请读取最新上下文");
 }
 public void bind(String user,String cid,long version,String action){var row=new AgentMandateQueryReceiptEntity();row.setActionId(action);row.setUserId(user);row.setConversationId(cid);row.setBoundVersion(version);require(receipts.insert(row));}
 /** No conversation lock here: reset takes conversation then receipt locks. */
 public void capture(String user,String action,List<MandateVO> data,Instant queried){var row=new AgentMandateQueryReceiptEntity();row.setActionId(action);row.setUserId(user);row.setQueriedAt(queried.atOffset(ZoneOffset.UTC));row.setExpiresAt(queried.plusSeconds(300).atOffset(ZoneOffset.UTC));try{row.setSnapshotJson(json.writeValueAsString(data));}catch(Exception e){throw new IllegalStateException(e);}row.setSnapshotHash(hash(row.getSnapshotJson()));int changed=receipts.capture(row);if(changed>1)throw new IllegalStateException("Unexpected receipt update count");}
 public Object read(String user,String cid){var row=receipts.latest(user,cid,clock.instant().atOffset(ZoneOffset.UTC));if(!eligible(row))return Map.of("code","QUERY_CONFIRMATION_REQUIRED","message","请在业务页确认代扣查询，再回到对话继续提问。");if(!hash(row.getSnapshotJson()).equals(row.getSnapshotHash()))throw new BankException("CONFLICT","代扣查询快照完整性校验失败");try{return Map.of("queryActionId",row.getActionId(),"snapshotHash",row.getSnapshotHash(),"mandates",json.readTree(row.getSnapshotJson()),"queriedAt",row.getQueriedAt().toInstant().toString(),"expiresAt",row.getExpiresAt().toInstant().toString());}catch(Exception e){throw new IllegalStateException(e);}}
 public void requireValidSnapshot(String user,String cid,com.fasterxml.jackson.databind.JsonNode stored){
  var row=receipts.findValid(user,cid,stored.path("queryActionId").asText(),clock.instant().atOffset(ZoneOffset.UTC));
  if(!eligible(row))throw new BankException("QUERY_CONFIRMATION_REQUIRED","查询回执已失效，请在业务页重新确认代扣查询。");
  if(!hash(row.getSnapshotJson()).equals(row.getSnapshotHash())||!row.getSnapshotHash().equals(stored.path("snapshotHash").asText())||!row.getQueriedAt().toInstant().equals(Instant.parse(stored.path("queriedAt").asText()))||!row.getExpiresAt().toInstant().equals(Instant.parse(stored.path("expiresAt").asText())))throw new BankException("CONFLICT","代扣查询快照完整性校验失败");
 }
 /** Receipt invalidation is a current locking read; terminal query status is immutable.
  * Do not acquire action/conversation locks after receipt locks (bank path uses the opposite order).
  * Gateway callers hold the live conversation lock throughout evidence validation.
  */
 private boolean eligible(AgentMandateQueryReceiptEntity row){
  if(row==null)return false;
  var conversation=conversations.selectOwned(row.getUserId(),row.getConversationId());
  var action=actions.selectOwned(row.getUserId(),row.getActionId());
  return conversation!=null&&!"EXPIRED".equals(conversation.getState())&&conversation.getExpiresAt().toInstant().isAfter(clock.instant())
   &&action!=null&&"QUERY_MANDATES".equals(action.getKind())&&"VERIFIED_SUCCESS".equals(action.getStatus());
 }
 public void invalidateConversation(String user,String cid){receipts.invalidateConversation(user,cid);}
 public void invalidateUser(String user){receipts.invalidateUser(user);}
}