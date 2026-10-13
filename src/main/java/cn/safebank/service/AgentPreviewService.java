package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.common.ActionStatus;
import cn.safebank.dao.AgentGatewayMapper;
import cn.safebank.dao.AgentTurnReceiptMapper;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.safebank.service.ContextEvidenceService.hash;

/** Semantic requests prepare one card. This service cannot authorize or submit. */
@Service @Profile("agent-gateway")
public class AgentPreviewService {
 private final AccountService accounts;private final RecipientService recipients;private final ActionService actions;
 private final AgentMandateReceiptService mandates;private final AgentGatewayMapper mapper;private final ObjectMapper json;private final Clock clock;
 @org.springframework.beans.factory.annotation.Autowired private AgentTurnReceiptMapper turnRows;
 public AgentPreviewService(AccountService a,RecipientService r,ActionService s,AgentMandateReceiptService m,AgentGatewayMapper g,ObjectMapper j,Clock c){accounts=a;recipients=r;actions=s;mandates=m;mapper=g;json=j;clock=c;}
 public static boolean isPreview(String tool){return Set.of("transfer_preview","revoke_preview").contains(tool);}
 private static String name(String value){return value.replace("（合成）","").replace("(合成)","").strip();}
 private static Map<String,Object> notice(String code){return Map.of("code",code);}
 public Object prepare(String user,String cid,String turn,String key,String tool,JsonNode args){
  if(mapper.receipts(turn).stream().anyMatch(r->isPreview(r.getToolName())))throw new BankException("PREVIEW_LIMIT","每轮最多生成一个操作预览");
  var previous=previewReceipts(user,cid);
  var latest=previous.stream().filter(row->stored(row).has("pendingAction")).max(Comparator.comparingLong(row->mapper.gateway(row.getTurnId()).getInputVersion()));
  String text=mapper.gateway(turn).getUserText();
  if(latest.isPresent()&&(text.contains("改")||text.contains("换"))){
   var old=actions.currentStatus(user,stored(latest.get()).path("pendingAction").path("id").asText());
   if(old.status()==ActionStatus.VERIFIED_SUCCESS)throw new BankException("ACTION_ALREADY_COMPLETED","原操作已完成，不能通过改参数修改；请核对原回执，新的操作需明确提出新需求");
  }
  // Invalidate unsubmitted old cards before returning a clarification notice.
  // On a business exception the transaction rolls back, so old cards stay unchanged.
  for(var row:previous){var data=stored(row);if(!data.has("pendingAction"))continue;
   String oldId=data.path("pendingAction").path("id").asText();var old=actions.currentStatus(user,oldId);
   if(Set.of(ActionStatus.PENDING,ActionStatus.UNKNOWN).contains(old.status()))throw new BankException("ACTION_RECONCILING","原动作仍在受理或状态未知，请核对原单后再生成新预览");
   if(Set.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED).contains(old.status()))actions.cancel(user,oldId);
  }
  String id="agent-"+hash(turn+"\n"+key).substring(0,48);
  ActionVO prepared;
  if(tool.equals("transfer_preview")){
   var sources=accounts.list(user).stream().filter(a->a.id().equals(args.path("sourceQuery").asText())||name(a.label()).equals(name(args.path("sourceQuery").asText()))).toList();
   var targets=recipients.search(user,args.path("recipientQuery").asText());
   if(targets.size()!=1)return notice("RECIPIENT_CLARIFICATION_REQUIRED");
   if(sources.size()!=1)return notice("SOURCE_CLARIFICATION_REQUIRED");
   prepared=actions.previewTransferIdentified(user,id,new TransferPreviewRequestDTO(sources.getFirst().id(),targets.getFirst().id(),args.path("amountMinor").longValue()));
  }else{
   var snapshot=json.valueToTree(mandates.read(user,cid));if(snapshot.has("code"))return notice("QUERY_CONFIRMATION_REQUIRED");
   mandates.requireValidSnapshot(user,cid,snapshot);
   var matches=new ArrayList<JsonNode>();for(var m:snapshot.path("mandates"))if(name(m.path("merchant").asText()).equals(name(args.path("merchantQuery").asText())))matches.add(m);
   if(matches.size()!=1)return notice("MANDATE_CLARIFICATION_REQUIRED");
   if(!matches.getFirst().path("status").asText().equals("ACTIVE"))return notice("MANDATE_NOT_ACTIVE");
   prepared=actions.previewRevokeIdentified(user,id,new MandatePreviewRequestDTO(matches.getFirst().path("id").asText()));
  }
  return Map.of("pendingAction",prepared);
 }
 private List<cn.safebank.entity.AgentToolCallReceiptEntity> previewReceipts(String user,String cid){
  // Use the existing indexed conversation->turn and turn->receipt paths.
  // Never scan/lock the global tool-receipt table by an unindexed predicate.
  return turnRows.selectByConversation(user,cid).stream().flatMap(turn->mapper.receipts(turn.getId()).stream()).filter(row->isPreview(row.getToolName())).toList();
 }
 private JsonNode stored(cn.safebank.entity.AgentToolCallReceiptEntity row){if(!hash(row.getResultJson()).equals(row.getResultHash()))throw new BankException("CONFLICT","预览回执完整性校验失败");try{return json.readTree(row.getResultJson());}catch(java.io.IOException e){throw new IllegalStateException(e);}}
 public Map<String,Object> facts(String user,JsonNode data){
  if(!data.has("pendingAction"))return Map.of();
  String id=data.path("pendingAction").path("id").asText();var current=actions.currentStatus(user,id);
  if(current.status()!=ActionStatus.AWAITING_AUTH||!current.expiresAt().toInstant().isAfter(clock.instant()))throw new BankException("PREVIEW_STALE","预览已改变或过期，请查看原操作卡状态");
  var p=current.parameters();
  if(p instanceof TransferParametersVO transfer){
   var recipient=recipients.owned(user,transfer.recipientId());
   return Map.of("recipient_label",recipient.getName(),"recipient_name",recipient.getName(),"recipient_note",recipient.getNote()==null?"未提供备注":recipient.getNote(),"amount_minor",transfer.amountMinor());
  }
  var revoke=(RevokeParametersVO)p;return Map.of("merchant",revoke.merchant(),"amount_minor",revoke.chargeAmountMinor());
 }
 public List<ActionVO> cards(String user,String cid){
  var cards=new LinkedHashMap<String,ActionVO>();for(var row:previewReceipts(user,cid)){var data=stored(row);if(data.has("pendingAction")){String id=data.path("pendingAction").path("id").asText();cards.put(id,actions.currentStatus(user,id));}}return List.copyOf(cards.values());
 }
}
