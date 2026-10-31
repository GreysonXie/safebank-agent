package cn.safebank.common;
import cn.safebank.BankException;
import com.fasterxml.jackson.databind.JsonNode;
import static cn.safebank.common.ContextTypes.*;
import java.time.*;
public final class ContextCodec {
    private ContextCodec() {}
    public static JsonNode decodeSlot(SlotName name,JsonNode value){
        boolean valid=switch(name){
            case AMOUNT_MINOR->value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>0&&value.longValue()<=1000000;
            case DAY_OF_MONTH->value.isIntegralNumber()&&value.intValue()>=1&&value.intValue()<=31;
            case PRIMARY_MONTH,COMPARISON_MONTH->month(value);
            case FIRST_DATE,END_DATE->date(value);
            case RECIPIENT_CANDIDATES,MANDATE_CANDIDATES,SELECTED_MANDATE_IDS,EXCLUDED_MANDATE_IDS,MISSING_SLOTS->value.isArray()&&value.size()<=10&&java.util.stream.StreamSupport.stream(value.spliterator(),false).allMatch(v->v.isTextual()&&v.asText().matches("[A-Za-z0-9_-]{1,80}"));
            default->value.isTextual()&&value.asText().matches("[A-Za-z0-9_-]{1,80}");
        };
        if(!valid)throw new BankException("VALIDATION_ERROR","业务参数类型或范围不合法："+name.key());
        return name==SlotName.AMOUNT_MINOR?com.fasterxml.jackson.databind.node.LongNode.valueOf(value.longValue()):value.deepCopy();
    }
    private static boolean month(JsonNode n){try{return n.isTextual()&&n.asText().matches("[0-9]{4}-[0-9]{2}")&&YearMonth.parse(n.asText())!=null;}catch(Exception e){return false;}}
    private static boolean date(JsonNode n){try{return n.isTextual()&&n.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")&&LocalDate.parse(n.asText())!=null;}catch(Exception e){return false;}}
}
