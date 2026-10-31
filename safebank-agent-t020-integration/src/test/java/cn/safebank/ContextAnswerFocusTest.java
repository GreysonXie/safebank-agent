package cn.safebank;
import cn.safebank.common.ContextTypes.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextAnswerFocusTest {
    @Test void transactionFallbackDoesNotRepeatWholeStatementSummary(){
        var composer=new BankReplyComposer(new ObjectMapper());
        var statement=Map.of("month","2026-09","expense_minor",50000,"income_minor",100000,"transactions",List.of(Map.of("id","repair","merchant","维修店","amount_minor",-40000,"booked_on","2026-09-03")),"categories",Map.of("维修",40000));
        String answer=composer.composeFocused(Intent.TRANSACTION_CHECK,"repair",List.of(new BankReplyComposer.Evidence("get_statement",statement)));
        assertTrue(answer.contains("维修店"));assertTrue(answer.contains("400.00"));assertFalse(answer.contains("净收支"));
    }
}
