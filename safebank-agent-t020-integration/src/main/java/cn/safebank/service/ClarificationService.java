package cn.safebank.service;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class ClarificationService {
    public ClarificationVO describe(ContextSnapshot s){
        var missing=new LinkedHashSet<>(ContextValidationService.ids(s.slots(),SlotName.MISSING_SLOTS));
        if(s.intent()==Intent.TRANSFER_DRAFT){for(var k:List.of(SlotName.SELECTED_RECIPIENT_ID,SlotName.AMOUNT_MINOR,SlotName.SOURCE_ACCOUNT_ID)){var value=s.slots().get(k);if(value==null||value.provenance()==SlotProvenance.MODEL_PROPOSED)missing.add(k.key());}}
        if((s.intent()==Intent.STATEMENT_ANALYSIS||s.intent()==Intent.TRANSACTION_CHECK)&&!s.slots().containsKey(SlotName.PRIMARY_MONTH))missing.add("primary_month");
        if(s.state()==ConversationState.PAUSED||missing.isEmpty())return null;
        var labels=Map.of("selected_recipient_id","收款人","amount_minor","金额","source_account_id","来源账户","primary_month","查询月份","first_date","首次日期","end_date","结束日期","day_of_month","每月执行日","selected_mandate_ids","要撤销的代扣");
        var candidates=new ArrayList<RecipientVO>();for(var e:s.evidence())if(e.toolName().equals("resolve_recipient"))for(var r:e.data())candidates.add(new RecipientVO(r.path("id").asText(),r.path("name").asText(),r.path("note").asText(),r.path("label").asText()));
        return new ClarificationVO("请补充或核对"+String.join("、",missing.stream().map(k->labels.getOrDefault(k,k)).toList())+"。",List.copyOf(missing),List.copyOf(candidates));
    }
}
