package cn.safebank;
import cn.safebank.common.ContextTypes.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextComparisonFocusTest {
    final ObjectMapper json=new ObjectMapper();
    List<BankReplyComposer.Evidence> statements(){return List.of(
        new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",450000,"categories",Map.of("居住",250000,"维修",200000))),
        new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-08","expense_minor",570000,"categories",Map.of("居住",250000,"购物",320000))));}
    @Test void comparisonFallbackAnswersLargestCategoryChange(){
        String answer=new BankReplyComposer(json).composeFocused(Intent.MONTH_COMPARISON,null,statements());
        assertTrue(answer.contains("购物"));assertTrue(answer.contains("减少"));assertTrue(answer.contains("3,200.00"));assertTrue(answer.contains("2026-09")&&answer.contains("2026-08"));assertFalse(answer.contains("净收支"));
    }
    @Test void categoryFactsIncludeDerivedZeroAndAbsoluteChange(){
        var facts=new GroundedAnswer(json,statements()).factCatalog();
        String prefix="comparison.statement_2026_09_vs_2026_08.categories.购物";
        assertTrue(facts.containsKey(prefix+".newer_minor"));
        assertEquals(0,facts.get(prefix+".newer_minor").number().longValueExact());
        assertEquals(-320000,facts.get(prefix+".difference_minor").number().longValueExact());
        assertEquals(320000,facts.get(prefix+".difference_magnitude_minor").number().longValueExact());
    }
}
