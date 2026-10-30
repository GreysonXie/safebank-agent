package cn.safebank;

import cn.safebank.dao.TransactionMapper;
import cn.safebank.entity.TransactionEntity;
import cn.safebank.service.StatementService;
import cn.safebank.vo.StatementFilterVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** T014：单月 + 类别 + 方向 + 单笔金额下限的组合筛选与事实接入（隔离夹具造行）。 */
class StatementFilterTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"),ZoneOffset.UTC);

    private TransactionEntity row(String user,String id,String category,long amount,String booked){
        var entity=new TransactionEntity();
        entity.setId(id);entity.setUserId(user);entity.setAccountId("checking");entity.setMerchant(id+"商户");
        entity.setCategory(category);entity.setAmountMinor(amount);entity.setBookedOn(LocalDate.parse(booked));
        return entity;
    }
    private StatementService service(List<TransactionEntity> rows){
        return new StatementService(new TransactionMapper(){
            public cn.safebank.entity.TransactionEntity selectActionDebit(String userId,String id){return null;}
            public List<cn.safebank.entity.TransactionEntity> selectPeriod(String userId,LocalDate start,LocalDate end){
                return rows.stream().filter(r->r.getUserId().equals(userId)&&!r.getBookedOn().isBefore(start)&&r.getBookedOn().isBefore(end)).toList();
            }
            public List<cn.safebank.dao.projection.RecurringPaymentRow> selectRecurring(String userId){return List.of();}
            public int insert(cn.safebank.entity.TransactionEntity row){return 1;}
        },clock);
    }
    /** 隔离夹具：边界 100.00 与 100.01、收入、内部转账、其他类别、其他用户。 */
    private final List<TransactionEntity> sample=List.of(
        row("u1","a","餐饮",-10000,"2026-09-02"),
        row("u1","b","餐饮",-10001,"2026-09-03"),
        row("u1","c","餐饮",-25000,"2026-09-04"),
        row("u1","d","购物",-30000,"2026-09-05"),
        row("u1","e","餐饮",120000,"2026-09-06"),
        row("u1","f","内部转账",-99000,"2026-09-07"),
        row("u2","g","餐饮",-50000,"2026-09-08"));

    @Test void amountFloorIsStrictlyGreaterAndTotalsMatchRows(){
        StatementFilterVO result=service(sample).filter("u1","2026-09","餐饮","支出",10000);
        assertEquals(2,result.matchedCount(),"100.00 不计入，100.01 与 250.00 计入");
        assertEquals(35001,result.expenseMinor());
        assertEquals(0,result.incomeMinor());
        assertEquals(2,result.transactions().size());
        assertEquals(35001,result.transactions().stream().mapToLong(r->Math.abs(r.amountMinor())).sum(),"合计等于匹配行之和");
        assertEquals(Map.of("餐饮",35001L),result.categories());
        assertEquals("expense",result.directionCode());
        assertEquals("2026-09-04",result.transactions().get(0).bookedOn().toString(),"逐笔按日期倒序");
    }

    @Test void incomeDirectionUsesItsOwnSignAndIgnoresExpenseFloorSemantics(){
        StatementFilterVO income=service(sample).filter("u1","2026-09","全部","收入",0);
        assertEquals(1,income.matchedCount());assertEquals(120000,income.incomeMinor());assertEquals(0,income.expenseMinor());
        StatementFilterVO expense=service(sample).filter("u1","2026-09","全部","支出",0);
        assertEquals(4,expense.matchedCount(),"内部转账不计入支出");
        assertEquals(10000+10001+25000+30000,expense.expenseMinor());
        assertEquals("income",income.directionCode());
    }

    @Test void unknownCategoryReturnsZeroWithEchoAndDoesNotWidenToAll(){
        StatementFilterVO result=service(sample).filter("u1","2026-09","旅行","支出",0);
        assertEquals(0,result.matchedCount());assertEquals(0,result.expenseMinor());
        assertEquals("旅行",result.category(),"回声原类别，不退成全量");
        assertEquals(List.of(),result.transactions());
    }

    @Test void userIsolationAndArbitraryMonthOrDirectionRejected(){
        assertEquals(2,service(sample).filter("u1","2026-09","餐饮","支出",10000).matchedCount());
        assertEquals(1,service(sample).filter("u2","2026-09","餐饮","支出",0).matchedCount(),"u2 只能看到自己的 500 元一笔");
        assertEquals(0,service(sample).filter("u2","2026-09","餐饮","支出",60000).matchedCount(),"u2 的 500 元不满足 600 元下限，也不掺入 u1 记录");
        assertThrows(BankException.class,()->service(sample).filter("u1","2026-13","餐饮","支出",0));
        assertThrows(BankException.class,()->service(sample).filter("u1","九月","餐饮","支出",0));
        assertThrows(BankException.class,()->service(sample).filter("u1","2026-09","餐饮","支出中",0));
        assertThrows(BankException.class,()->service(sample).filter("u1","2026-09","餐饮","支出",-1));
        assertThrows(BankException.class,()->service(sample).filter("u1","2026-09","  ","支出",0));
    }

    @Test void filterArgsContractRejectsBadTypesAndExtraKeys(){
        assertEquals(10000,StatementService.parseFilterArgs(json.valueToTree(Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",10000))).minAmountMinor());
        for(Map<String,Object> bad:List.<Map<String,Object>>of(
            Map.of("month","2026-09","category","餐饮","direction","支出"),
            Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",10000,"userId","u2"),
            Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor","10000"),
            Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",true),
            Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",-5),
            Map.of("month","2026-9","category","餐饮","direction","支出","minAmountMinor",0),
            Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",new java.math.BigInteger("9223372036854775808")))){
            assertThrows(BankException.class,()->StatementService.parseFilterArgs(json.valueToTree(bad)),bad.toString());
        }
    }

    @Test void monthlyCompareAndSingleMonthAnswersAreUnaffectedByTheFilterRule(){
        Map<String,Object> september=Map.of("month","2026-09","expense_minor",165001,"income_minor",120000,"categories",Map.of(),"transactions",List.of(),"anomaly_candidates",List.of());
        Map<String,Object> august=Map.of("month","2026-08","expense_minor",150000,"income_minor",950000,"categories",Map.of(),"transactions",List.of(),"anomaly_candidates",List.of());
        var answer=new GroundedAnswer(json,List.of(
            new BankReplyComposer.Evidence("get_statement",september),
            new BankReplyComposer.Evidence("get_statement",august)));
        var catalog=answer.modelCatalog("对比8月和9月支出");
        assertTrue(catalog.containsKey("statement_2026_09.expense_minor"),"无筛选回执时整月合计照常进入模型目录");
        assertTrue(catalog.containsKey("comparison.statement_2026_09_vs_2026_08.expense_difference_minor"),"原有月度比较事实不受影响");
        answer.render(json.valueToTree(Map.of("kind","analysis","answer","9月支出 {{statement_2026_09.expense_minor}}，比8月少 {{comparison.statement_2026_09_vs_2026_08.expense_difference_magnitude_minor}}。")));
    }
    @Test void twoFiltersInOneTurnDoNotShareValuesAndBindToLatestScope(){
        var food=service(sample).filter("u1","2026-09","餐饮","支出",10000);
        var shopping=service(sample).filter("u1","2026-09","购物","支出",0);
        String foodPrefix="statement_filter_2026_09_expense_min10000_餐饮";
        String shoppingPrefix="statement_filter_2026_09_expense_min0_购物";
        Map<?,?> foodResult=json.convertValue(food,Map.class),shoppingResult=json.convertValue(shopping,Map.class);
        var answer=new GroundedAnswer(json,List.of(
            new BankReplyComposer.Evidence("get_statement_filter",foodResult),
            new BankReplyComposer.Evidence("get_statement_filter",shoppingResult)));
        var catalog=answer.modelCatalog("对比这两组");
        assertTrue(catalog.containsKey(shoppingPrefix+".expense_minor"),"最新一次筛选在模型目录中");
        assertFalse(catalog.containsKey(foodPrefix+".expense_minor"),"较早一次筛选不进入模型目录");
        assertEquals(new BigDecimal("30000"),answer.factCatalog().get(shoppingPrefix+".expense_minor").number());
        assertEquals(new BigDecimal("35001"),answer.factCatalog().get(foodPrefix+".expense_minor").number(),"两份筛选各自的值不串");
        assertEquals(shoppingPrefix,String.valueOf(answer.modelToolResult("get_statement_filter",shoppingResult).toString().replaceAll(".*fact_prefix=([^,}]+).*","$1")));
        assertEquals(foodPrefix,String.valueOf(answer.modelToolResult("get_statement_filter",foodResult).toString().replaceAll(".*fact_prefix=([^,}]+).*","$1")));
        answer.render(json.valueToTree(Map.of("kind","analysis","answer","购物合计 {{"+shoppingPrefix+".expense_minor}}。")));
        assertThrows(BankException.class,()->answer.render(json.valueToTree(Map.of("kind","analysis","answer","购物合计 {{"+shoppingPrefix+".expense_minor}}，餐饮合计 {{"+foodPrefix+".expense_minor}}。"))),
            "回答绑定的筛选条件与本次查询不一致时被拒");
    }
    @Test void factsUseConditionPrefixAndWholeMonthTotalIsNotUsableAsFilteredTotal(){
        StatementFilterVO result=service(sample).filter("u1","2026-09","餐饮","支出",10000);
        var filtered=new GroundedAnswer(json,List.of(
            new BankReplyComposer.Evidence("get_statement_filter",json.convertValue(result,Map.class))));
        var facts=filtered.factCatalog();
        String prefix="statement_filter_2026_09_expense_min10000_餐饮";
        assertTrue(facts.containsKey(prefix+".expense_minor"),"前缀由条件生成");
        assertEquals(new BigDecimal("35001"),facts.get(prefix+".expense_minor").number());
        assertEquals(new BigDecimal("2"),facts.get(prefix+".matched_count").number());
        assertEquals(prefix,filtered.factCatalog().keySet().stream().filter(key->key.startsWith("statement_filter_")).findFirst().get().replaceFirst("\\..*",""),"回执 fact_prefix 与目录前缀一致");
        filtered.render(json.valueToTree(Map.of("kind","analysis","answer","餐饮超过 100 元共 {{"+prefix+".matched_count}} 笔，合计 {{"+prefix+".expense_minor}}。")));
        assertThrows(BankException.class,()->filtered.render(json.valueToTree(Map.of("kind","analysis","answer","合计 {{statement_2026_09.expense_minor}}。"))),
            "筛选轮不能用整月合计冒充筛选合计");
        var both=new GroundedAnswer(json,List.of(
            new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",165001,"income_minor",120000,"categories",Map.of(),"transactions",List.of(),"anomaly_candidates",List.of())),
            new BankReplyComposer.Evidence("get_statement_filter",json.convertValue(result,Map.class))));
        assertTrue(both.factCatalog().containsKey("statement_2026_09.expense_minor"),"事实目录仍保留整月事实供页面核对");
        assertFalse(both.modelCatalog("餐饮超过100元哪几笔").containsKey("statement_2026_09.expense_minor"),"同一轮存在筛选回执时，整月合计不进入模型目录");
        assertThrows(BankException.class,()->both.render(json.valueToTree(Map.of("kind","analysis","answer","合计 {{statement_2026_09.expense_minor}}。"))));
    }
}
