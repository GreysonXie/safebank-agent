package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.SimulationClock;
import cn.safebank.dao.TransactionMapper;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
@Service public class StatementService {
    /** 单笔支出达到 2000 元仅作候选，不是风险评估结论；月报与年报共用这一口径。 */
    static final String ANOMALY_RULE="单笔支出达到 2000 元，仅为候选，不能推断欺诈；本人账户内部转账不计收入支出";
    /** 明细可见范围说明：目录可能因容量或隔离省略行，省略不代表没有记录。 */
    static final String FILTER_SCOPE_NOTE="合计按全部匹配记录计算；明细可能因容量或隔离被省略，省略不代表没有记录";
    private static final long ANOMALY_THRESHOLD_MINOR=200000;
    private final TransactionMapper transactions;
    private final Clock clock;
    public StatementService(TransactionMapper transactions,Clock clock){
        this.transactions=transactions;this.clock=clock;
    }
    public StatementVO statement(String userId,String month){
        YearMonth period=month(month);
        var entries=entries(userId,period.atDay(1),period.plusMonths(1).atDay(1));
        var totals=aggregate(entries);
        return new StatementVO(month,totals.expense(),totals.income(),totals.internal(),totals.categories(),entries,totals.anomalies(),ANOMALY_RULE,false);
    }
    /**
     * 年度结果由同一年每个月的单月聚合相加得到，金额口径与 {@link #statement} 完全一致（同一段聚合代码）。
     * coverage 只说明本轮实际取到的记录日期与业务日期，不声明数据完整。
     */
    public YearStatementVO yearStatement(String userId,String year){
        int value=year(year);
        var entries=entries(userId,LocalDate.of(value,1,1),LocalDate.of(value+1,1,1));
        var byMonth=new TreeMap<String,List<TransactionVO>>();
        for(var entry:entries)if(entry.bookedOn()!=null)byMonth.computeIfAbsent(YearMonth.from(entry.bookedOn()).toString(),key->new ArrayList<>()).add(entry);
        long expense=0,income=0,internal=0;
        var categories=new TreeMap<String,Long>();
        var months=new ArrayList<YearStatementVO.MonthRow>();
        for(int m=1;m<=12;m++){
            String label=YearMonth.of(value,m).toString();
            var rows=byMonth.getOrDefault(label,List.of());
            var totals=aggregate(rows);
            expense+=totals.expense();income+=totals.income();internal+=totals.internal();
            totals.categories().forEach((category,total)->categories.merge(category,total,Long::sum));
            months.add(new YearStatementVO.MonthRow(label,totals.expense(),totals.income(),totals.internal(),rows.size()));
        }
        LocalDate businessDate=businessDate();
        LocalDate first=null,last=null;
        for(var entry:entries){
            var booked=entry.bookedOn();
            if(booked==null)continue;
            if(first==null||booked.isBefore(first))first=booked;
            if(last==null||booked.isAfter(last))last=booked;
        }
        var coverage=new YearStatementVO.Coverage(businessDate.toString(),value==businessDate.getYear(),
            first==null?null:first.toString(),last==null?null:last.toString(),last!=null&&last.isAfter(businessDate));
        return new YearStatementVO(String.valueOf(value),expense,income,internal,categories,months,entries.size(),coverage);
    }
    /** 单月 + 类别 + 收支方向 + 单笔金额下限（严格大于）的受控组合筛选；合计与明细同源。 */
    public StatementFilterVO filter(String userId,String month,String category,String direction,long minAmountMinor){
        YearMonth period=month(month);
        String normalized=category==null?"":category.trim();
        if(normalized.isEmpty())throw new BankException("VALIDATION_ERROR","请提供类别名称或“全部”");
        boolean expense=switch(direction==null?"":direction){case "支出"->true;case "收入"->false;default->throw new BankException("VALIDATION_ERROR","方向只能是支出或收入");};
        if(minAmountMinor<0)throw new BankException("VALIDATION_ERROR","金额下限不能为负数");
        var rows=new ArrayList<TransactionVO>();
        var categories=new TreeMap<String,Long>();
        long total=0;
        for(var entry:entries(userId,period.atDay(1),period.plusMonths(1).atDay(1))){
            long amount=entry.amountMinor();
            if("内部转账".equals(entry.category()))continue;
            if(expense?amount>=0:amount<=0)continue;
            if(!"全部".equals(normalized)&&!normalized.equals(entry.category()))continue;
            long magnitude=Math.abs(amount);
            if(magnitude<=minAmountMinor)continue;
            rows.add(entry);total+=magnitude;
            categories.merge(Objects.toString(entry.category(),""),magnitude,Long::sum);
        }
        rows.sort(Comparator.comparing(TransactionVO::bookedOn,Comparator.nullsLast(Comparator.reverseOrder())));
        return new StatementFilterVO(month,normalized,direction,expense?"expense":"income",minAmountMinor,
            expense?total:0,expense?0:total,rows.size(),FILTER_SCOPE_NOTE,categories,List.copyOf(rows));
    }
    /** 事实层参数契约：恰好四个键，类型与范围按同一套规则核对（REST 与工具共用）。 */
    public record StatementFilterArgs(String month,String category,String direction,long minAmountMinor){
        public static final Set<String> KEYS=Set.of("month","category","direction","minAmountMinor");
    }
    public static StatementFilterArgs parseFilterArgs(com.fasterxml.jackson.databind.JsonNode args){
        if(args==null||!args.isObject())throw new BankException("VALIDATION_ERROR","筛选参数必须是对象");
        var keys=new HashSet<String>();args.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(StatementFilterArgs.KEYS))throw new BankException("VALIDATION_ERROR","筛选参数只接受 month、category、direction、minAmountMinor");
        try{YearMonth.parse(args.path("month").asText());}catch(Exception invalid){throw new BankException("VALIDATION_ERROR","月份格式应为 YYYY-MM");}
        String category=args.path("category").asText().trim();
        if(category.isEmpty()||category.length()>40)throw new BankException("VALIDATION_ERROR","类别必须为 1 至 40 个字符");
        String direction=args.path("direction").asText();
        if(!"支出".equals(direction)&&!"收入".equals(direction))throw new BankException("VALIDATION_ERROR","方向只能是支出或收入");
        var node=args.path("minAmountMinor");
        if(!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<0)throw new BankException("VALIDATION_ERROR","金额下限必须是非负整数分");
        return new StatementFilterArgs(args.path("month").asText(),category,direction,node.asLong());
    }
    public List<String> months(String userId){
        return transactions.selectBookedDates(userId).stream().map(date->YearMonth.from(date).toString()).distinct().toList();
    }
    public List<String> years(String userId){
        return transactions.selectBookedDates(userId).stream().map(date->String.valueOf(date.getYear())).distinct().sorted(Comparator.reverseOrder()).toList();
    }
    public List<RecurringPaymentVO> recurring(String userId){
        var result=new ArrayList<RecurringPaymentVO>();
        for(var r:transactions.selectRecurring(userId)){
            double average=(double)ChronoUnit.DAYS.between(r.firstSeen(),r.lastSeen())/(r.occurrences()-1);
            if(average>=25&&average<=35)result.add(new RecurringPaymentVO(r.merchant(),r.occurrences(),r.firstSeen(),r.lastSeen(),r.minAmountMinor(),r.maxAmountMinor(),"周期扣费候选",false));
        }
        return result;
    }
    public LocalDate businessDate(){return clock==null?LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")):SimulationClock.businessDate(clock);}
    private record Aggregate(long expense,long income,long internal,Map<String,Long> categories,List<TransactionVO> anomalies) {}
    /** 月报与年报唯一的金额口径实现：内部转账不计收支，退款计入入账，支出不减退款。 */
    private Aggregate aggregate(List<TransactionVO> entries){
        long expense=0,income=0,internal=0;
        var categories=new TreeMap<String,Long>();
        var anomalies=new ArrayList<TransactionVO>();
        for(var entry:entries){
            long amount=entry.amountMinor();
            if("内部转账".equals(entry.category())){
                if(amount<0)internal-=amount;
                continue;
            }
            if(amount<0){
                expense-=amount;
                categories.merge(Objects.toString(entry.category(),""),-amount,Long::sum);
                if(-amount>=ANOMALY_THRESHOLD_MINOR)anomalies.add(entry);
            }
            else income+=amount;
        }
        return new Aggregate(expense,income,internal,categories,anomalies);
    }
    private List<TransactionVO> entries(String userId,LocalDate start,LocalDate end){
        return transactions.selectPeriod(userId,start,end).stream().map(r->new TransactionVO(r.getId(),r.getMerchant(),r.getCategory(),r.getAmountMinor(),r.getBookedOn())).toList();
    }
    private YearMonth month(String month){
        try{
            return YearMonth.parse(month);
        }
        catch(Exception e){
            throw new BankException("VALIDATION_ERROR","月份格式应为 YYYY-MM");
        }
    }
    private int year(String year){
        if(year==null||!year.matches("20[0-9]{2}"))throw new BankException("VALIDATION_ERROR","年份格式应为 YYYY");
        return Integer.parseInt(year);
    }
}
