package cn.safebank.agent;
import cn.safebank.common.ContextTypes.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class BusinessToolSelector {
    private final ObjectMapper json;
    public BusinessToolSelector(ObjectMapper json){this.json=json;}
    public List<Object> select(Intent intent,List<Object> definitions){
        var allowed=new HashSet<>(Set.of("answer_customer","calculate_business_metric","get_action_status","get_workflow"));
        switch(intent){
            case STATEMENT_ANALYSIS,TRANSACTION_CHECK,MONTH_COMPARISON->allowed.addAll(List.of("get_statement","detect_recurring_payments"));
            case TRANSFER_DRAFT->allowed.addAll(List.of("resolve_recipient","get_accounts","preview_transfer"));
            case MANDATE_QUERY,MANDATE_REVOKE_DRAFT->allowed.addAll(List.of("list_mandates","detect_recurring_payments","preview_revoke"));
            case SAVINGS_WORKFLOW->allowed.addAll(List.of("list_mandates","get_statement","get_accounts","resolve_recipient","preview_schedule","start_savings_workflow"));
            case GENERAL,CLARIFICATION->allowed.addAll(List.of("get_statement","get_accounts","resolve_recipient","list_mandates","detect_recurring_payments"));
            case ACTION_STATUS->allowed.add("list_transfer_schedules");
        }
        var result=new ArrayList<Object>();for(var d:definitions)if(allowed.contains(json.valueToTree(d).path("function").path("name").asText()))result.add(d);
        result.add(Map.of("type","function","function",Map.of("name","propose_business_context","description","记录本轮业务意图与客户明确选择、缺失参数。只更新待核对草案，不授权和执行。updates键只用业务槽：primary_month,comparison_month,focused_transaction_id,selected_recipient_id,source_account_id,amount_minor,selected_mandate_ids,excluded_mandate_ids,first_date,end_date,day_of_month。金额整数分，不指定身份或动作ID。",
            "parameters",Map.of("type","object","properties",Map.of("intent",Map.of("type","string","enum",Arrays.stream(Intent.values()).map(Enum::name).toList()),"updates",Map.of("type","object"),"clears",Map.of("type","array","items",Map.of("type","string")),"missingSlots",Map.of("type","array","items",Map.of("type","string")),"pause",Map.of("type","boolean")),"required",List.of("intent","updates","clears","missingSlots","pause"),"additionalProperties",false))));
        return List.copyOf(result);
    }
}
