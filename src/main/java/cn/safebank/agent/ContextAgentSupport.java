package cn.safebank.agent;
import cn.safebank.BankException;
import cn.safebank.BankReplyComposer;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import cn.safebank.dto.ContextProposalDTO;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
@Component public class ContextAgentSupport {
    private record Frame(TurnLease lease,String userText,ContextSnapshot snapshot) {}
    private final ThreadLocal<Frame> current=new ThreadLocal<>();
    private final ContextValidationService validation;private final ContextBankProposalService proposals;private final ContextEvidenceService evidence;private final BusinessContextBuilder builder;private final BusinessToolSelector selector;private final ObjectMapper json;private final Clock clock;
    public ContextAgentSupport(ContextValidationService v,ContextBankProposalService p,ContextEvidenceService e,BusinessContextBuilder b,BusinessToolSelector s,ObjectMapper j,Clock c){validation=v;proposals=p;evidence=e;builder=b;selector=s;json=j;clock=c;}
    public void open(TurnLease lease,ContextSnapshot snapshot,String text){current.set(new Frame(lease,text,validation.infer(lease,snapshot,text)));}
    public void close(){current.remove();}
    public ContextSnapshot snapshot(){return current.get().snapshot();}
    public List<Map<String,Object>> messages(){var f=current.get();return builder.messages(f.lease().userId(),f.snapshot(),f.userText());}
    public List<Object> definitions(List<Object> all){return selector.select(snapshot().intent(),all);}
    public List<BankReplyComposer.Evidence> remembered(){
        var rows=new ArrayList<BankReplyComposer.Evidence>();var latest=new LinkedHashMap<String,EvidenceInput>();
        for(var e:snapshot().evidence()){
            String key=e.toolName().equals("get_statement")?e.key():e.toolName();var old=latest.get(key);if(old==null||e.observedAt().isAfter(old.observedAt()))latest.put(key,e);
        }
        for(var e:latest.values())rows.add(new BankReplyComposer.Evidence(e.toolName(),json.convertValue(e.data(),Object.class),observation(e)));return rows;
    }
    public static Map<String,Object> observation(EvidenceInput e){
        var result=new LinkedHashMap<String,Object>();result.put("observed_at",e.observedAt().toString());result.put("historical_snapshot",true);
        if(e.objectId()!=null)result.put("bank_object_id",e.objectId());
        if(e.toolName().equals("list_mandates")){result.put("query_scope","OWN_BANK_MANDATES");result.put("note","仅表示原确认查询时的银行侧授权；不是实时状态，不证明商户合同或其他扣款渠道");}
        return result;
    }
    public Object dispatch(String name,String id,JsonNode args,Supplier<Object> normal){
        var f=current.get();
        if(name.equals("propose_business_context")){
            ContextProposalDTO p;try{p=json.treeToValue(args,ContextProposalDTO.class);}catch(Exception e){throw new BankException("VALIDATION_ERROR","业务草案参数无效");}
            update(validation.stage(f.lease(),f.snapshot(),f.userText(),p));return Map.of("context",ContextService.view(snapshot()),"authorizationGranted",false);
        }
        Object result;
        if(Set.of("preview_transfer","preview_revoke","preview_schedule","list_mandates","start_savings_workflow").contains(name)){
            validation.requirePreview(f.lease(),f.snapshot(),name,args);result=proposals.preview(f.lease(),id,name,args);
        }else result=normal.get();
        record(name,result);return result;
    }
    private void record(String name,Object result){
        var f=current.get();var data=json.valueToTree(result);var s=f.snapshot();
        if(name.equals("list_mandates")&&data.path("pendingAction").has("id")||name.startsWith("preview_")&&data.has("id")){
            var action=data.has("id")?data:data.path("pendingAction");s=validation.withServerSlot(f.lease(),s,SlotName.ACTIVE_ACTION_ID,action.path("id"),null);s=new ContextSnapshot(s.conversationId(),s.version(),s.intent(),ConversationState.AWAITING_CONFIRMATION,s.slots(),action,s.evidence(),s.displayHistory());update(s);return;
        }
        if(name.equals("start_savings_workflow")&&data.has("id")){s=validation.withServerSlot(f.lease(),s,SlotName.ACTIVE_WORKFLOW_ID,data.path("id"),null);update(s);return;}
        if(data.has("code")||!Set.of("get_statement","resolve_recipient","detect_recurring_payments").contains(name))return;
        String key=name.equals("get_statement")?"get_statement:"+data.path("month").asText():name;
        var input=new EvidenceInput(key,name,null,data,clock.instant(),null);evidence.store(f.lease(),List.of(input));
        var all=evidence.load(f.lease().userId(),f.lease().conversationId());s=new ContextSnapshot(s.conversationId(),s.version(),s.intent(),s.state(),s.slots(),s.proposal(),all,s.displayHistory());
        String ref=evidence.referenceId(f.lease().userId(),f.lease().conversationId(),key);
        if(name.equals("get_statement")&&!s.slots().containsKey(SlotName.PRIMARY_MONTH))s=validation.withServerSlot(f.lease(),s,SlotName.PRIMARY_MONTH,data.path("month"),ref);
        if(name.equals("resolve_recipient")){var ids=json.createArrayNode();for(var r:data)ids.add(r.path("id").asText());s=validation.withServerSlot(f.lease(),s,SlotName.RECIPIENT_CANDIDATES,ids,ref);}
        update(s);
    }
    public String fallback(BankReplyComposer composer,List<BankReplyComposer.Evidence> inputs){
        var s=snapshot();var focus=s.slots().get(SlotName.FOCUSED_TRANSACTION_ID);var primary=s.slots().get(SlotName.PRIMARY_MONTH);var comparison=s.slots().get(SlotName.COMPARISON_MONTH);
        var combined=new LinkedHashMap<String,BankReplyComposer.Evidence>();var all=new ArrayList<>(remembered());all.addAll(inputs);
        for(var e:all){var d=json.valueToTree(e.data());String key=e.tool()+":"+d.path("month").asText();if(d.has("code"))key+=":"+d.path("code").asText();combined.put(key,e);}
        return composer.composeFocused(s.intent(),focus==null?null:focus.value().asText(),primary==null?null:primary.value().asText(),comparison==null?null:comparison.value().asText(),List.copyOf(combined.values()));
    }
    private void update(ContextSnapshot s){var f=current.get();current.set(new Frame(f.lease(),f.userText(),s));}
}
