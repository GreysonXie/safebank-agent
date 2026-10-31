package cn.safebank;

import cn.safebank.dao.TransactionMapper;
import cn.safebank.dao.projection.RecurringPaymentRow;
import cn.safebank.entity.TransactionEntity;
import cn.safebank.service.StatementService;
import cn.safebank.vo.StatementFilterVO;
import cn.safebank.vo.StatementVO;
import cn.safebank.vo.YearStatementVO;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 账单细颗粒度：年度报告与“月份+类别+方向+金额下限”组合筛选的口径、边界与隔离。 */
class StatementGranularityTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"),ZoneOffset.UTC);

    private TransactionEntity row(String user,String id,String category,long amount,String booked){
        var entity=new TransactionEntity();
        entity.setId(id);entity.setUserId(user);entity.setAccountId("checking");entity.setMerchant(id+"商户");
        entity.setCategory(category);entity.setAmountMinor(amount);entity.setBookedOn(LocalDate.parse(booked));
        return entity;
    }
    /** 隔离夹具：2026-01/02/09 跨月、内部转账、退款收入、上一年、其他用户。 */
    private final List<TransactionEntity> sample=List.of(
        row("u1","t1","餐饮",-12345,"2026-01-05"),
        row("u1","t2","收入",500000,"2026-01-06"),
        row("u1","t3","内部转账",-40000,"2026-02-01"),
        row("u1","t4","购物",-99999,"2026-02-11"),
        row("u1","t5","退款",320000,"2026-09-30"),
        row("u1","t6","餐饮",-5000,"2025-12-31"),
        row("u2","o1","餐饮",-777777,"2026-03-03"));

    private StatementService service(List<TransactionEntity> rows){
        return new StatementService(new TransactionMapper(){
            public List<java.time.LocalDate> selectBookedDates(String userId){
                return rows.stream().filter(row->row.getUserId().equals(userId)).map(TransactionEntity::getBookedOn).sorted(Comparator.reverseOrder()).toList();
            }
            public TransactionEntity selectActionDebit(String userId,String id){return null;}
            public List<TransactionEntity> selectPeriod(String userId,LocalDate start,LocalDate end){
                return rows.stream().filter(row->row.getUserId().equals(userId)&&!row.getBookedOn().isBefore(start)&&row.getBookedOn().isBefore(end)).toList();
            }
            public List<RecurringPaymentRow> selectRecurring(String userId){return List.of();}
            public int insert(TransactionEntity row){return 1;}
        },clock);
    }

    @Test void yearTotalsEqualTheSumOfItsMonths(){
        var service=service(sample);
        YearStatementVO year=service.yearStatement("u1","2026");
        long expense=0,income=0,internal=0;var categories=new TreeMap<String,Long>();
        for(int month=1;month<=12;month++){
            StatementVO single=service.statement("u1",String.format("2026-%02d",month));
            expense+=single.expenseMinor();income+=single.incomeMinor();internal+=single.internalTransferMinor();
            single.categories().forEach((category,total)->categories.merge(category,total,Long::sum));
        }
        assertEquals(expense,year.expenseMinor());assertEquals(income,year.incomeMinor());
        assertEquals(internal,year.internalTransferMinor());assertEquals(categories,year.categories());
        assertEquals(12345+99999,year.expenseMinor());assertEquals(500000+320000,year.incomeMinor());
        assertEquals(40000,year.internalTransferMinor());assertEquals(5,year.recordCount());
        assertEquals(12,year.months().size());
        assertEquals(12345,year.months().get(0).expenseMinor());assertEquals(2,year.months().get(0).transactionCount());
        assertEquals(0,year.months().get(10).transactionCount(),"没有记录的月份如实为 0，不编造");
        assertFalse(year.categories().containsKey("内部转账"),"内部转账不进入支出类别");
    }

    @Test void emptyYearReportsNoRecordsWithoutInventingData(){
        var year=service(sample).yearStatement("u1","2024");
        assertEquals(0,year.recordCount());assertEquals(0,year.expenseMinor());assertEquals(0,year.incomeMinor());
        assertTrue(year.categories().isEmpty());assertEquals(12,year.months().size());
        assertNull(year.coverage().firstBookedOn());assertNull(year.coverage().lastBookedOn());
        assertFalse(year.coverage().yearInProgress());
    }

    @Test void coverageAndProgressComeFromActualRecords(){
        var year=service(sample).yearStatement("u1","2026");
        assertEquals("2026-01-05",year.coverage().firstBookedOn());
        assertEquals("2026-09-30",year.coverage().lastBookedOn());
        assertEquals("2026-10-30",year.coverage().businessDate());
        assertTrue(year.coverage().yearInProgress());assertFalse(year.coverage().beyondBusinessDate());
    }

    @Test void otherUsersRecordsNeverEnterTheYearOrTheFilter(){
        var service=service(sample);
        assertEquals(0,service.yearStatement("u2","2026").incomeMinor());
        assertEquals(777777,service.yearStatement("u2","2026").expenseMinor());
        assertEquals(12345+99999,service.yearStatement("u1","2026").expenseMinor());
        assertEquals(1,service.filter("u2","2026-03","全部","支出",0).matchedCount());
        assertEquals(0,service.filter("u1","2026-03","全部","支出",0).matchedCount());
    }

    @Test void filterAmountFloorIsStrictlyGreaterAndTotalsMatchRows(){
        var rows=List.of(
            row("u1","a","餐饮",-10000,"2026-09-02"),
            row("u1","b","餐饮",-10001,"2026-09-03"),
            row("u1","c","餐饮",-25000,"2026-09-04"),
            row("u1","d","购物",-30000,"2026-09-05"),
            row("u1","e","餐饮",120000,"2026-09-06"),
            row("u1","f","内部转账",-99000,"2026-09-07"));
        StatementFilterVO result=service(rows).filter("u1","2026-09","餐饮","支出",10000);
        assertEquals(2,result.matchedCount(),"100.00 不计入，100.01 与 250.00 计入");
        assertEquals(35001,result.expenseMinor());
        assertEquals(0,result.incomeMinor());
        assertEquals(2,result.transactions().size());
        assertEquals(35001,result.transactions().stream().mapToLong(row->Math.abs(row.amountMinor())).sum(),"合计等于匹配行之和");
        assertEquals(Map.of("餐饮",35001L),result.categories());
        assertEquals("expense",result.directionCode());
        assertEquals("2026-09-04",result.transactions().get(0).bookedOn().toString(),"逐笔按日期倒序");
    }

    @Test void filterDirectionSemanticsAndValidation(){
        var rows=List.of(
            row("u1","a","餐饮",-10000,"2026-09-02"),
            row("u1","b","餐饮",-10001,"2026-09-03"),
            row("u1","c","餐饮",-25000,"2026-09-04"),
            row("u1","d","购物",-30000,"2026-09-05"),
            row("u1","e","餐饮",120000,"2026-09-06"),
            row("u1","f","内部转账",-99000,"2026-09-07"));
        var service=service(rows);
        StatementFilterVO income=service.filter("u1","2026-09","全部","收入",0);
        assertEquals(1,income.matchedCount());assertEquals(120000,income.incomeMinor());assertEquals(0,income.expenseMinor());
        assertEquals("income",income.directionCode());
        StatementFilterVO expense=service.filter("u1","2026-09","全部","支出",0);
        assertEquals(4,expense.matchedCount(),"内部转账不计入支出");
        assertEquals(10000+10001+25000+30000,expense.expenseMinor());
        StatementFilterVO unknown=service.filter("u1","2026-09","旅行","支出",0);
        assertEquals(0,unknown.matchedCount());assertEquals("旅行",unknown.category(),"回声原类别，不退成全量");
        assertEquals(List.of(),unknown.transactions());
        assertThrows(BankException.class,()->service.filter("u1","2026-13","餐饮","支出",0));
        assertThrows(BankException.class,()->service.filter("u1","九月","餐饮","支出",0));
        assertThrows(BankException.class,()->service.filter("u1","2026-09","餐饮","支出中",0));
        assertThrows(BankException.class,()->service.filter("u1","2026-09","餐饮","支出",-1));
        assertThrows(BankException.class,()->service.filter("u1","2026-09","  ","支出",0));
        assertThrows(BankException.class,()->service.yearStatement("u1","2026-1"));
        assertThrows(BankException.class,()->service.yearStatement("u1",null));
    }

    @Test void yearsAndMonthsListsComeFromRealRecords(){
        var service=service(sample);
        assertEquals(List.of("2026","2025"),service.years("u1"),"年份倒序且只来自真实记录");
        assertEquals(List.of("2026-09","2026-02","2026-01","2025-12"),service.months("u1"));
        assertEquals(List.of("2026"),service.years("u2"));
    }
}
