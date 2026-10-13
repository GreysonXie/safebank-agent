package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.common.ContextContracts.*;
import cn.safebank.dao.AgentTurnReceiptMapper;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class ContextBankProposalService {
    private final ContextService contexts;private final AgentTurnReceiptMapper turns;private final ActionService actions;private final WorkflowService workflows;private final RiskUserLockService locks;private final ObjectMapper json;
    public ContextBankProposalService(ContextService c,AgentTurnReceiptMapper t,ActionService a,WorkflowService w,RiskUserLockService l,ObjectMapper j){contexts=c;turns=t;actions=a;workflows=w;locks=l;json=j;}
    public Object preview(TurnLease lease,String callId,String name,JsonNode args){
        if(!Set.of("preview_transfer","preview_revoke","preview_schedule","list_mandates","start_savings_workflow").contains(name))throw new BankException("PERMISSION_DENIED","不是允许的银行预览");
        String hash=ContextEvidenceService.hash(name+canonical(args));
        // User lock is first; nested bank services join this short transaction.
        return locks.withUserLock(lease.userId(),()->{
            var turn=contexts.turn(lease);if(!turn.getState().equals("PROCESSING"))throw new BankException("CONTEXT_INTERRUPTED","原轮次已结束，不能重放创建操作");
            var intents=decode(turn.getToolIntentsJson(),new TypeReference<List<ToolIntent>>(){});
            var old=intents.stream().filter(i->i.toolCallId().equals(callId)).findFirst();
            if(old.isPresent()){
                if(!old.get().parametersHash().equals(hash))throw new BankException("CONFLICT","同一工具请求改变了参数");
                return existing(lease.userId(),name,old.get().bankObjectId());
            }
            // Across turns reuse the active identical proposal, never a completed/cancelled money operation.
            for(var previous:turns.selectByConversation(lease.userId(),lease.conversationId()))for(var intent:decode(previous.getToolIntentsJson(),new TypeReference<List<ToolIntent>>(){})){
                if(!intent.toolName().equals(name)||intent.bankObjectId()==null)continue;
                if(name.equals("start_savings_workflow")){
                    var w=workflows.get(lease.userId(),UUID.fromString(intent.bankObjectId()));
                    if(!Set.of("CANCELLED","VERIFIED_SUCCESS","VERIFIED_FAILED").contains(w.state())){
                        if(!intent.parametersHash().equals(hash))throw new BankException("CONFLICT","原联合任务尚未结束，请核对或修改原任务，不能另建重复任务");
                        bind(turn,intents,new ToolIntent(callId,name,hash,intent.bankObjectId(),"CREATED"),name);return w;
                    }
                    continue;
                }
                if(!intent.parametersHash().equals(hash))continue;
                var a=actions.status(lease.userId(),intent.bankObjectId());
                if(Set.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED,ActionStatus.PENDING,ActionStatus.UNKNOWN).contains(a.status())){
                    bind(turn,intents,new ToolIntent(callId,name,hash,a.id(),"CREATED"),name);return existing(lease.userId(),name,a.id());
                }
            }
            String id=UUID.randomUUID().toString();Object result=switch(name){
                case "preview_transfer"->actions.previewTransferIdentified(lease.userId(),id,new TransferPreviewRequestDTO(text(args,"source_id"),text(args,"recipient_id"),amount(args)));
                case "preview_revoke"->actions.previewRevokeIdentified(lease.userId(),id,new MandatePreviewRequestDTO(text(args,"mandate_id")));
                case "preview_schedule"->actions.previewScheduleIdentified(lease.userId(),id,new SchedulePreviewRequestDTO(text(args,"source_id"),text(args,"recipient_id"),amount(args),text(args,"first_date"),text(args,"end_date"),args.path("day_of_month").asInt()));
                case "list_mandates"->actions.prepareIdentified(lease.userId(),id,ActionKind.QUERY_MANDATES,new QueryParametersVO("OWN_BANK_MANDATES"));
                case "start_savings_workflow"->workflows.createIdentified(lease.userId(),UUID.fromString(id),new WorkflowRequestDTO(text(args,"month"),strings(args.path("mandate_ids")),text(args,"source_id"),text(args,"recipient_id"),text(args,"first_date"),text(args,"end_date"),args.path("day_of_month").asInt(),args.path("only_confirmed_savings").asBoolean()));
                default->throw new IllegalStateException();
            };
            bind(turn,intents,new ToolIntent(callId,name,hash,id,"CREATED"),name);
            return name.equals("list_mandates")?Map.of("pendingAction",result,"code","QUERY_CONFIRMATION_REQUIRED","message","请确认读取本人银行侧代扣列表"):name.equals("start_savings_workflow")?result:json.convertValue(result,Object.class);
        });
    }
    private void bind(cn.safebank.entity.AgentTurnReceiptEntity t,List<ToolIntent> intents,ToolIntent intent,String name){
        var next=new ArrayList<>(intents);next.add(intent);var refs=new ArrayList<>(decode(t.getBankReferencesJson(),new TypeReference<List<BankReference>>(){}));refs.add(new BankReference(intent.toolCallId(),name.equals("start_savings_workflow")?"WORKFLOW":"ACTION",intent.bankObjectId()));t.setToolIntentsJson(encode(next));t.setBankReferencesJson(encode(refs));ContextEvidenceService.require(turns.updateOwned(t));
    }
    private Object existing(String user,String name,String id){Object value=name.equals("start_savings_workflow")?workflows.get(user,UUID.fromString(id)):actions.status(user,id);return name.equals("list_mandates")?Map.of("pendingAction",value,"code","QUERY_CONFIRMATION_REQUIRED","message","请核对原查询动作"):name.equals("start_savings_workflow")?value:json.convertValue(value,Object.class);}
    private String canonical(JsonNode node){if(node.isObject()){var result=json.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);for(String key:names)result.set(key,node.get(key));return result.toString();}return node.toString();}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T> List<T> decode(String v,TypeReference<List<T>> type){if(v==null)return List.of();try{return json.readValue(v,type);}catch(Exception e){throw new BankException("CONFLICT","轮次银行关联不可读取");}}
    private static String text(JsonNode n,String k){if(!n.path(k).isTextual()||n.path(k).asText().isBlank())throw new BankException("VALIDATION_ERROR","缺少参数 "+k);return n.path(k).asText();}
    private static long amount(JsonNode n){if(!n.path("amount_minor").isIntegralNumber()||!n.path("amount_minor").canConvertToLong())throw new BankException("VALIDATION_ERROR","金额须为整数分");return n.path("amount_minor").longValue();}
    private static List<String> strings(JsonNode n){if(!n.isArray()||n.isEmpty()||n.size()>10)throw new BankException("VALIDATION_ERROR","代扣选择无效");var result=new ArrayList<String>();for(var v:n){if(!v.isTextual()||v.asText().isBlank())throw new BankException("VALIDATION_ERROR","代扣选择无效");result.add(v.asText());}return result;}
}
