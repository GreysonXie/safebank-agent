package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.*;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import cn.safebank.dto.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
@Service public class ContextValidationService {
    private final RecipientService recipients;private final AccountService accounts;private final ActionService actions;private final WorkflowService workflows;private final ObjectMapper json;private final Clock clock;
    public ContextValidationService(RecipientService r,AccountService a,ActionService actions,WorkflowService workflows,ObjectMapper j,Clock c){recipients=r;accounts=a;this.actions=actions;this.workflows=workflows;json=j;clock=c;}
    public ContextSnapshot stage(TurnLease l,ContextSnapshot old,String text,ContextProposalDTO p){
        var values=new EnumMap<SlotName,SlotValue>(SlotName.class);values.putAll(old.slots());
        var updates=p.updates()==null?Map.<String,JsonNode>of():p.updates();
        for(var key:p.clears()==null?List.<String>of():p.clears()){
            var slot=SlotName.from(key);if(Set.of(SlotName.ACTIVE_ACTION_ID,SlotName.ACTIVE_WORKFLOW_ID).contains(slot))throw invalid("不能直接清除银行动作关联");values.remove(slot);
        }
        for(var entry:updates.entrySet()){
            var slot=SlotName.from(entry.getKey());if(Set.of(SlotName.RECIPIENT_CANDIDATES,SlotName.MANDATE_CANDIDATES,SlotName.ACTIVE_ACTION_ID,SlotName.ACTIVE_WORKFLOW_ID,SlotName.MISSING_SLOTS).contains(slot))throw invalid("该上下文项只能由服务器生成");
            var value=ContextCodec.decodeSlot(slot,entry.getValue());
            boolean selectionNegated=(slot==SlotName.SELECTED_RECIPIENT_ID||slot==SlotName.SOURCE_ACCOUNT_ID)&&text.matches("(?s).*(不要|不是|不选|不用|别选|别用|不转给).*" );
            var previous=old.slots().get(slot);if(previous!=null&&previous.value().equals(value)&&previous.provenance()!=SlotProvenance.MODEL_PROPOSED&&!selectionNegated&&!negatedChoice(text,value.asText())){values.put(slot,previous);continue;}
            boolean explicit=explicit(l,old,slot,value,text);
            values.put(slot,new SlotValue(value,explicit?SlotProvenance.USER_EXPLICIT:SlotProvenance.MODEL_PROPOSED,l.turnId(),null));
        }
        if(updates.containsKey("excluded_mandate_ids")&&values.get(SlotName.EXCLUDED_MANDATE_IDS).provenance()==SlotProvenance.USER_EXPLICIT&&!updates.containsKey("selected_mandate_ids")){
            var keep=ids(values,SlotName.EXCLUDED_MANDATE_IDS);var remain=ids(values,SlotName.SELECTED_MANDATE_IDS).stream().filter(id->!keep.contains(id)).toList();values.put(SlotName.SELECTED_MANDATE_IDS,new SlotValue(json.valueToTree(remain),SlotProvenance.USER_EXPLICIT,l.turnId(),null));
        }
        var selected=ids(values,SlotName.SELECTED_MANDATE_IDS);var excluded=ids(values,SlotName.EXCLUDED_MANDATE_IDS);
        if(selected.stream().anyMatch(excluded::contains))throw invalid("同一代扣不能同时选中和保留");
        boolean paused=pauseRequested(text);if(p.pause()&&!paused)throw invalid("没有客户明确暂停指令");
        if(old.slots().containsKey(SlotName.ACTIVE_WORKFLOW_ID)){
            var workflow=workflows.get(l.userId(),UUID.fromString(old.slots().get(SlotName.ACTIVE_WORKFLOW_ID).value().asText()));
            if(paused&&!Set.of("CANCELLED","VERIFIED_SUCCESS","PARTIAL_SUCCESS").contains(workflow.state()))workflows.cancel(l.userId(),workflow.id(),workflow.version());
            else if(updates.containsKey("excluded_mandate_ids")&&values.get(SlotName.EXCLUDED_MANDATE_IDS).provenance()==SlotProvenance.USER_EXPLICIT){
                var r=workflow.request();var remaining=r.mandateIds().stream().filter(id->!excluded.contains(id)).toList();
                if(!remaining.equals(r.mandateIds())){
                    if(remaining.isEmpty())workflows.cancel(l.userId(),workflow.id(),workflow.version());
                    else workflows.revise(l.userId(),workflow.id(),workflow.version(),new WorkflowRequestDTO(r.month(),remaining,r.sourceId(),r.recipientId(),r.firstDate(),r.endDate(),r.dayOfMonth(),true));
                }
            }
        }
        boolean parametersChanged=Set.of(SlotName.AMOUNT_MINOR,SlotName.SELECTED_RECIPIENT_ID,SlotName.SOURCE_ACCOUNT_ID,SlotName.FIRST_DATE,SlotName.END_DATE,SlotName.DAY_OF_MONTH,SlotName.SELECTED_MANDATE_IDS).stream().anyMatch(k->{
            var before=old.slots().get(k);var after=values.get(k);
            return before!=null&&(after==null||!before.value().equals(after.value())||before.provenance()!=SlotProvenance.MODEL_PROPOSED&&after.provenance()==SlotProvenance.MODEL_PROPOSED);
        });
        if((paused||parametersChanged)&&old.slots().containsKey(SlotName.ACTIVE_ACTION_ID)){
            String id=old.slots().get(SlotName.ACTIVE_ACTION_ID).value().asText();var a=actions.status(l.userId(),id);
            if(Set.of(ActionStatus.UNKNOWN,ActionStatus.PENDING).contains(a.status()))throw new BankException("CONFLICT","原动作已受理或结果未知，先核对原动作，不能改参或重复创建");
            if(Set.of(ActionStatus.AWAITING_AUTH,ActionStatus.AUTHORIZED).contains(a.status()))actions.cancel(l.userId(),id);
            else if(parametersChanged&&a.status()==ActionStatus.VERIFIED_SUCCESS)throw new BankException("CONFLICT","已完成的银行动作无法通过改口修改");
            values.remove(SlotName.ACTIVE_ACTION_ID);
        }
        var missing=p.missingSlots()==null?List.<String>of():p.missingSlots();for(String k:missing)SlotName.from(k);
        if(missing.size()>10)throw invalid("待补参数过多");if(!missing.isEmpty())values.put(SlotName.MISSING_SLOTS,new SlotValue(json.valueToTree(missing),SlotProvenance.MODEL_PROPOSED,l.turnId(),null));else values.remove(SlotName.MISSING_SLOTS);
        var state=paused?ConversationState.PAUSED:missing.isEmpty()?ConversationState.ACTIVE:ConversationState.NEEDS_CLARIFICATION;
        return new ContextSnapshot(old.conversationId(),old.version(),p.intent()==null?old.intent():p.intent(),state,Map.copyOf(values),old.proposal(),old.evidence(),old.displayHistory());
    }
    public ContextSnapshot infer(TurnLease l,ContextSnapshot s,String text){
        var updates=new LinkedHashMap<String,JsonNode>();Intent intent=s.intent();
        boolean compare=text.matches("(?s).*(比较|相比|对比|和.*比|比.*月).*"),transfer=text.matches("(?s).*(转账|转钱|转[0-9]+元|给.+转).*"),mandate=text.matches("(?s).*(订阅|代扣|音乐|视频).*"),check=text.matches("(?s).*(那笔|这笔|维修|大额|核对).*"),savings=text.contains("储蓄")||text.contains("存起来");
        if(transfer)intent=Intent.TRANSFER_DRAFT;else if(savings)intent=Intent.SAVINGS_WORKFLOW;else if(compare)intent=Intent.MONTH_COMPARISON;else if(mandate)intent=text.contains("取消")||text.contains("停")?Intent.MANDATE_REVOKE_DRAFT:Intent.MANDATE_QUERY;else if(check)intent=Intent.TRANSACTION_CHECK;else if(text.contains("账单"))intent=Intent.STATEMENT_ANALYSIS;
        var month=Pattern.compile("(?<![0-9])([0-9]{4})-([0-9]{2})(?![0-9])").matcher(text);String chosen=null;if(month.find())chosen=month.group();
        if(chosen==null){var m=Pattern.compile("(十二|十一|十|九|八|七|六|五|四|三|二|一|[1-9]|1[0-2])月").matcher(text);if(m.find()){int n=switch(m.group(1)){case "一"->1;case "二"->2;case "三"->3;case "四"->4;case "五"->5;case "六"->6;case "七"->7;case "八"->8;case "九"->9;case "十"->10;case "十一"->11;case "十二"->12;default->Integer.parseInt(m.group(1));};chosen=YearMonth.of(LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).getYear(),n).toString();}}
        if(chosen!=null)updates.put(compare&&s.slots().containsKey(SlotName.PRIMARY_MONTH)?"comparison_month":"primary_month",json.valueToTree(chosen));
        Long amount=amountFrom(text);if(amount!=null&&(intent==Intent.TRANSFER_DRAFT||text.contains("改成")||text.contains("改为")))updates.put("amount_minor",json.valueToTree(amount));
        var result=stage(l,s,text,new ContextProposalDTO(intent,updates,List.of(),List.of(),pauseRequested(text)));
        if(intent==Intent.TRANSACTION_CHECK){
            String monthFilter=chosen==null?s.slots().containsKey(SlotName.PRIMARY_MONTH)?s.slots().get(SlotName.PRIMARY_MONTH).value().asText():null:chosen;
            var rows=new ArrayList<JsonNode>();for(var e:s.evidence())if(e.toolName().equals("get_statement")&&(monthFilter==null||e.data().path("month").asText().equals(monthFilter)))for(var row:e.data().path("transactions"))if(row.path("amount_minor").asLong()<0)rows.add(row);
            JsonNode focus=null;if(text.contains("最大")&&!rows.isEmpty())focus=rows.stream().min(Comparator.comparingLong(r->r.path("amount_minor").asLong())).orElse(null);
            else {var matching=rows.stream().filter(r->text.contains("维修")&&(r.path("merchant").asText().contains("维修")||r.path("category").asText().contains("维修"))).toList();if(matching.size()==1)focus=matching.getFirst();}
            if(focus!=null)result=withServerSlot(l,result,SlotName.FOCUSED_TRANSACTION_ID,focus.path("id"),null);
        }
        return result;
    }
    public ContextSnapshot withServerSlot(TurnLease l,ContextSnapshot s,SlotName name,JsonNode value,String evidenceId){var values=new EnumMap<SlotName,SlotValue>(SlotName.class);values.putAll(s.slots());values.put(name,new SlotValue(ContextCodec.decodeSlot(name,value),SlotProvenance.BANK_CONFIRMED,l.turnId(),evidenceId));return new ContextSnapshot(s.conversationId(),s.version(),s.intent(),s.state(),Map.copyOf(values),s.proposal(),s.evidence(),s.displayHistory());}
    public void requirePreview(TurnLease l,ContextSnapshot s,String name,JsonNode a){
        if(s.state()==ConversationState.PAUSED)throw new BankException("PERMISSION_DENIED","任务已暂停，未创建新预览");
        if(name.equals("list_mandates"))return;
        if(name.equals("preview_transfer")||name.equals("preview_schedule")||name.equals("start_savings_workflow")){
            require(s,SlotName.SELECTED_RECIPIENT_ID,a.path("recipient_id"));require(s,SlotName.SOURCE_ACCOUNT_ID,a.path("source_id"));
            if(!name.equals("start_savings_workflow"))require(s,SlotName.AMOUNT_MINOR,a.path("amount_minor"));
        }
        if(name.equals("preview_revoke")){var selected=s.slots().get(SlotName.SELECTED_MANDATE_IDS);if(selected==null||selected.provenance()!=SlotProvenance.USER_EXPLICIT||!ids(s.slots(),SlotName.SELECTED_MANDATE_IDS).contains(a.path("mandate_id").asText()))throw invalid("请明确选择要撤销的代扣");}
        if(name.equals("preview_schedule")||name.equals("start_savings_workflow")){require(s,SlotName.FIRST_DATE,a.path("first_date"));require(s,SlotName.END_DATE,a.path("end_date"));require(s,SlotName.DAY_OF_MONTH,a.path("day_of_month"));}
        if(name.equals("start_savings_workflow")){var selected=s.slots().get(SlotName.SELECTED_MANDATE_IDS);if(selected==null||selected.provenance()!=SlotProvenance.USER_EXPLICIT||!selected.value().equals(a.path("mandate_ids")))throw invalid("联合储蓄需要明确选择代扣");}
    }
    private static void require(ContextSnapshot s,SlotName name,JsonNode v){var slot=s.slots().get(name);if(slot==null||slot.provenance()==SlotProvenance.MODEL_PROPOSED||!slot.value().equals(ContextCodec.decodeSlot(name,v)))throw invalid("请明确或核对参数："+name.key());}
    private boolean explicit(TurnLease l,ContextSnapshot old,SlotName name,JsonNode v,String text){
        if(name==SlotName.AMOUNT_MINOR)return Objects.equals(amountFrom(text),v.longValue());
        if(name==SlotName.SELECTED_RECIPIENT_ID){var r=recipients.owned(l.userId(),v.asText());if(negatedChoice(text,v.asText())||negatedChoice(text,r.getNote())||negatedChoice(text,r.getName()))return false;if(positiveChoice(text,v.asText()))return true;if(positiveChoice(text,r.getNote()))return true;if(positiveChoice(text,r.getName())&&recipients.search(l.userId(),r.getName()).size()==1)return true;var candidates=ids(old.slots(),SlotName.RECIPIENT_CANDIDATES);int index=positiveChoice(text,"第二个")?1:positiveChoice(text,"第一个")?0:-1;return index>=0&&candidates.size()>index&&candidates.get(index).equals(v.asText());}
        if(name==SlotName.SOURCE_ACCOUNT_ID){accounts.balance(l.userId(),v.asText());return positiveChoice(text,v.asText())||accounts.list(l.userId()).stream().anyMatch(a->a.id().equals(v.asText())&&positiveChoice(text,a.label()));}
        if(name==SlotName.SELECTED_MANDATE_IDS||name==SlotName.EXCLUDED_MANDATE_IDS){
            for(var id:v){var known=old.evidence().stream().filter(e->e.toolName().equals("list_mandates")).flatMap(e->java.util.stream.StreamSupport.stream(e.data().spliterator(),false)).filter(r->r.path("id").asText().equals(id.asText())).toList();if(known.isEmpty())return false;String merchant=known.getLast().path("merchant").asText();boolean choice=false;
                for(String clause:text.split("[，,。；;]")){boolean mentions=clause.contains(id.asText())||clause.contains(merchant)||clause.contains("音乐")&&merchant.contains("音乐")||clause.contains("视频")&&merchant.contains("视频");if(!mentions)continue;
                    boolean keep=clause.matches("(?s).*(保留|不取消|别取消|不要取消|不撤销|不停).*"),remove=clause.matches("(?s).*(取消|撤销|停).*");
                    if(name==SlotName.EXCLUDED_MANDATE_IDS?keep:remove&&!keep)choice=true;
                }
                if(!choice)return false;
            }return true;
        }
        if(name==SlotName.FOCUSED_TRANSACTION_ID)return old.evidence().stream().filter(e->e.toolName().equals("get_statement")).anyMatch(e->{for(var row:e.data().path("transactions"))if(row.path("id").asText().equals(v.asText()))return true;return false;});
        if(name==SlotName.PRIMARY_MONTH||name==SlotName.COMPARISON_MONTH)return text.contains(v.asText())||text.matches("(?s).*(月).*" );
        if(name==SlotName.FIRST_DATE||name==SlotName.END_DATE)return text.contains(v.asText());
        if(name==SlotName.DAY_OF_MONTH)return text.contains(v.asInt()+"号")||text.contains(v.asInt()+"日")||old.slots().containsKey(SlotName.FIRST_DATE)&&old.slots().get(SlotName.FIRST_DATE).provenance()==SlotProvenance.USER_EXPLICIT&&LocalDate.parse(old.slots().get(SlotName.FIRST_DATE).value().asText()).getDayOfMonth()==v.asInt();
        return false;
    }
    private static boolean negatedChoice(String text,String token){return token!=null&&!token.isBlank()&&Pattern.compile("(?:不要|别选|不选|不是|不用|别用|不转给|不要给)[^，,。；;]{0,16}"+Pattern.quote(token)).matcher(text).find();}
    private static boolean positiveChoice(String text,String token){return token!=null&&!token.isBlank()&&text.contains(token)&&!negatedChoice(text,token);}
    public static boolean pauseRequested(String text){return text.matches("(?s).*(暂停|先别[^，。；]{0,30}(?:转|执行|继续|提交)|先不[^，。；]{0,30}(?:转|执行|继续|提交)|不要[^，。；]{0,30}转|别转|暂不(?:执行|转)).*");}
    private static Long amountFrom(String text){var m=Pattern.compile("(?:改成|改为|金额(?:为|是)?)\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|块)").matcher(text);Long found=null;while(m.find()){String number=m.group(1)==null?m.group(2):m.group(1);try{long v=new BigDecimal(number).movePointRight(2).longValueExact();if(found!=null&&found!=v)return null;found=v;}catch(Exception e){return null;}}return found;}
    public static List<String> ids(Map<SlotName,SlotValue> values,SlotName name){var v=values.get(name);if(v==null)return List.of();var ids=new ArrayList<String>();for(var id:v.value())ids.add(id.asText());return ids;}
    private static BankException invalid(String message){return new BankException("VALIDATION_ERROR",message);}
}
