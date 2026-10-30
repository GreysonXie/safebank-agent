package cn.safebank;

import cn.safebank.dao.TransactionMapper;
import cn.safebank.dao.projection.RecurringPaymentRow;
import cn.safebank.entity.TransactionEntity;
import cn.safebank.service.StatementService;
import cn.safebank.vo.StatementVO;
import cn.safebank.vo.YearStatementVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 年度账单：与月报同口径、空年份、跨用户隔离，以及年度回执能否生成可引用事实。 */
class YearStatementTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"),ZoneOffset.UTC);

    private TransactionEntity row(String user,String id,String category,long amount,String booked){
        var entity=new TransactionEntity();
        entity.setId(id);entity.setUserId(user);entity.setAccountId("checking");entity.setMerchant(id+"商户");
        entity.setCategory(category);entity.setAmountMinor(amount);entity.setBookedOn(LocalDate.parse(booked));
        return entity;
    }
    private TransactionMapper mapper(List<TransactionEntity> rows){
        var mapper=org.mockito.Mockito.mock(TransactionMapper.class);
        org.mockito.Mockito.when(mapper.selectPeriod(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            String user=call.getArgument(0);LocalDate start=call.getArgument(1),end=call.getArgument(2);
            return rows.stream().filter(r->r.getUserId().equals(user)&&!r.getBookedOn().isBefore(start)&&r.getBookedOn().isBefore(end)).toList();
        });
        return mapper;
    }
    private final List<TransactionEntity> sample=List.of(
        row("u1","t1","餐饮",-12345,"2026-01-05"),
        row("u1","t2","收入",500000,"2026-01-06"),
        row("u1","t3","内部转账",-40000,"2026-02-01"),
        row("u1","t4","购物",-99999,"2026-02-11"),
        row("u1","t5","退款",320000,"2026-09-30"),
        row("u1","t6","餐饮",-5000,"2025-12-31"),
        row("u2","o1","餐饮",-777777,"2026-03-03"));

    @Test void yearTotalsEqualTheSumOfItsMonths(){
        var service=new StatementService(mapper(sample),clock);
        var year=service.yearStatement("u1","2026");
        long expense=0,income=0,internal=0;var categories=new TreeMap<String,Long>();
        for(int m=1;m<=12;m++){
            StatementVO month=service.statement("u1",String.format("2026-%02d",m));
            expense+=month.expenseMinor();income+=month.incomeMinor();internal+=month.internalTransferMinor();
            month.categories().forEach((category,total)->categories.merge(category,total,Long::sum));
        }
        assertEquals(expense,year.expenseMinor());assertEquals(income,year.incomeMinor());
        assertEquals(internal,year.internalTransferMinor());assertEquals(categories,year.categories());
        assertEquals(12345+99999,year.expenseMinor());assertEquals(500000+320000,year.incomeMinor());
        assertEquals(40000,year.internalTransferMinor());assertEquals(6-1,year.recordCount());
        assertEquals(12,year.months().size());
        assertEquals(12345,year.months().get(0).expenseMinor());assertEquals(2,year.months().get(0).transactionCount());
        assertEquals(0,year.months().get(10).transactionCount());
    }
    @Test void emptyYearReportsNoRecordsWithoutInventingData(){
        var year=new StatementService(mapper(sample),clock).yearStatement("u1","2024");
        assertEquals(0,year.recordCount());assertEquals(0,year.expenseMinor());assertEquals(0,year.incomeMinor());
        assertTrue(year.categories().isEmpty());assertEquals(12,year.months().size());
        assertNull(year.coverage().firstBookedOn());assertNull(year.coverage().lastBookedOn());
        assertFalse(year.coverage().yearInProgress());
    }
    @Test void coverageAndProgressComeFromActualRecords(){
        var year=new StatementService(mapper(sample),clock).yearStatement("u1","2026");
        assertEquals("2026-01-05",year.coverage().firstBookedOn());
        assertEquals("2026-09-30",year.coverage().lastBookedOn());
        assertEquals("2026-10-30",year.coverage().businessDate());
        assertTrue(year.coverage().yearInProgress());assertFalse(year.coverage().beyondBusinessDate());
    }
    @Test void otherUsersRecordsNeverEnterTheYear(){
        var service=new StatementService(mapper(sample),clock);
        assertEquals(0,service.yearStatement("u2","2026").incomeMinor());
        assertEquals(777777,service.yearStatement("u2","2026").expenseMinor());
        assertEquals(12345+99999,service.yearStatement("u1","2026").expenseMinor());
    }
    @Test void monthlyStatementIsScopedByUser(){
        var service=new StatementService(mapper(sample),clock);
        assertEquals(12345,service.statement("u1","2026-01").expenseMinor());
        assertEquals(0,service.statement("u2","2026-01").expenseMinor());
        assertEquals(0,service.statement("u1","2026-03").expenseMinor());
        assertEquals(777777,service.statement("u2","2026-03").expenseMinor());
        assertEquals(1,service.statement("u2","2026-03").transactions().size());
    }
    @Test void yearReceiptProducesReferenceableFactsAndMonthsStillWork(){
        var year=new StatementService(mapper(sample),clock).yearStatement("u1","2026");
        var answer=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_year_statement",json.convertValue(year,Map.class))));
        var facts=answer.factCatalog();
        assertTrue(facts.containsKey("statement_year_2026.expense_minor"));
        assertTrue(facts.containsKey("statement_year_2026.months.0.expense_minor"));
        assertTrue(facts.containsKey("statement_year_2026.categories."+"餐饮"));
        assertTrue(facts.containsKey("statement_year_2026.record_count"));
        assertEquals(new BigDecimal("112344"),facts.get("statement_year_2026.expense_minor").number());
        assertEquals(List.of("2026"),answer.businessContext().get("available_statement_years"));
        var text=answer.render(json.valueToTree(Map.of("kind","analysis","answer","2026年支出合计{{statement_year_2026.expense_minor}}，其中1月支出{{statement_year_2026.months.0.expense_minor}}。"))).text();
        assertTrue(text.contains("1,123.44"));assertTrue(text.contains("123.45"));assertFalse(text.contains("{{"));
        var monthly=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",0,"income_minor",0,"categories",Map.of(),"transactions",List.of(),"anomaly_candidates",List.of()))));
        assertTrue(monthly.factCatalog().containsKey("statement_2026_09.expense_minor"));
        assertEquals(List.of(),monthly.businessContext().get("available_statement_years"));
    }
}
