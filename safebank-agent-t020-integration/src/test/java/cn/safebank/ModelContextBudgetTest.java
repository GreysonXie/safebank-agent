package cn.safebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelContextBudgetTest {
    private final ObjectMapper json=new ObjectMapper();
    private GroundedAnswer facts() {
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<180;i++)rows.add(Map.of("id","order-"+i,"merchant","日常消费商户"+i,"category","日用品","amount_minor",-100,"booked_on","2026-09-10"));
        rows.add(Map.of("id","refund-unrelated","merchant","退款","category","退款","amount_minor",320000,"booked_on","2026-09-12"));
        Map<String,Object> large=Map.of("id","rent","merchant","租金","category","居住","amount_minor",-250000,"booked_on","2026-09-01");rows.add(large);
        return new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",268000,"income_minor",320000,"categories",Map.of("居住",250000,"日用品",18000),"transactions",rows,"anomaly_candidates",List.of(large)))));
    }
    @Test void summaryContextIsSmallButPreservesLargeExpenseAndCategoryEvidence() throws Exception {
        var facts=facts();var compact=facts.modelCatalog("分析2026-09账单");
        assertTrue(compact.containsKey("statement_2026_09.expense_minor"));
        assertTrue(compact.containsKey("statement_2026_09.categories.日用品"));
        assertTrue(compact.containsKey("statement_2026_09.anomaly_candidates.0.booked_on"));
        assertFalse(compact.containsKey("statement_2026_09.transactions.179.merchant"));
        assertTrue(json.writeValueAsString(compact).length()<json.writeValueAsString(facts.factCatalog()).length()/2);
        // Compaction affects only outgoing model context; original facts remain usable for verification.
        assertEquals("日常消费商户179",facts.factCatalog().get("statement_2026_09.transactions.179.merchant").display().replace("〔","").replace("〕",""));
        assertEquals("支出1.00元。",facts.render(json.valueToTree(Map.of("answer","支出{{statement_2026_09.transactions.179.spending_minor}}。","kind","analysis"))).text());
    }
    @Test void detailQuestionsAndExplicitReadExpansionRestoreEveryTransaction() {
        var facts=facts();assertTrue(facts.modelCatalog("逐笔看交易明细").containsKey("statement_2026_09.transactions.179.id"));
        assertTrue(facts.modelContext("分析账单").contains("include_transactions"));
        facts.includeTransactions("2026-09");
        assertTrue(facts.modelCatalog("分析账单").containsKey("statement_2026_09.transactions.179.id"));
        assertEquals(facts.factCatalog().size(),facts.modelCatalog("逐笔看交易明细").size());
    }
    @Test void equalAmountsCannotEstablishRefundOrderRelationship() {
        var facts=facts();
        for(String claim:List.of("这笔购物在九月以退款形式入账。","这笔退款对应上月那笔消费。","The refund corresponds to the previous purchase."))
            assertEquals("ANSWER_NOT_GROUNDED",assertThrows(BankException.class,()->facts.render(json.valueToTree(Map.of("answer",claim,"kind","general")))).code,claim);
        for(String safe:List.of("这笔退款可能对应那笔购物，但还需订单关联凭证。","不能确认这笔退款对应上月那笔消费。","这笔退款是否对应那笔购物？","上月购物和本月退款分别记录，不能确认是同一订单。"))
            assertEquals(safe,facts.render(json.valueToTree(Map.of("answer",safe,"kind","general"))).text());
        assertEquals(false,((Map<?,?>)facts.businessContext().get("capabilities")).get("transaction_refund_linkage"));
    }
    @Test void comparisonCalculationAndHypothesisRetainProvenanceInCompactViews() {
        var facts=facts();facts.addEvidence(new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-08","expense_minor",300000,"income_minor",850000)));
        var catalog=json.valueToTree(facts.modelCatalog("比较八月和九月"));
        assertEquals("2026-09相对2026-08",catalog.path("comparison.statement_2026_09_vs_2026_08.expense_direction").path("source").asText());
        assertTrue(catalog.path("comparison.statement_2026_09_vs_2026_08.expense_difference_minor").path("source").asText().contains("2026-09"));
        assertTrue(facts.modelReviewCatalog().containsKey("comparison.e0_e1.expense_direction"));
        facts.calculate(json.valueToTree(Map.of("operation","SUM","facts",List.of("statement_2026_09.expense_minor","statement_2026_08.expense_minor"))));
        assertTrue(json.valueToTree(facts.modelCatalog("比较八月和九月")).path("calc0").path("source").asText().contains("statement_2026_08"));
        facts.render(json.valueToTree(Map.of("answer","如果设定预算目标为50元，可以再核对需要。","kind","general")));
        assertTrue(json.valueToTree(facts.modelCatalog("预算建议")).path("hypothesis0").path("source").asText().contains("显式假设"));
    }
    @Test void separatelyDescribingPurchasesAndRefundsMustRemainAllowed() {
        var facts=facts();
        for(String safe:List.of("这笔购物支出和退款入账应分别描述。","上月购物记录与本月退款记录应分开核对。","退款对应原订单是指业务上的关联关系，并不是金额相同就能认定。","Refunds and purchases should be reviewed separately."))
            assertEquals(safe,facts.render(json.valueToTree(Map.of("answer",safe,"kind","general"))).text());
    }
    @Test void comparisonViewAlwaysUsesEarlierMonthAsBaseRegardlessOfQueryOrder() {
        for(boolean olderFirst:List.of(true,false)) {
            var older=new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-08","expense_minor",654792));
            var newer=new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",536933));
            var facts=new GroundedAnswer(json,olderFirst?List.of(older,newer):List.of(newer,older));
            var catalog=json.valueToTree(facts.modelCatalog("比较八月和九月"));
            var prefix="comparison.statement_2026_09_vs_2026_08";
            assertEquals("减少",catalog.path(prefix+".expense_direction").path("display").asText());
            assertEquals(18.0,catalog.path(prefix+".expense_change_magnitude_percent").path("number").asDouble());
            assertEquals("2026-09相对2026-08",catalog.path(prefix+".expense_change_magnitude_percent").path("source").asText().split("，")[0]);
            assertTrue(facts.modelCatalog("比较八月和九月").keySet().stream().noneMatch(id->id.startsWith("comparison.e")));
        }
    }
}
