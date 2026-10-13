package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.TransactionMapper;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
@Service public class StatementService {
    private final TransactionMapper transactions;
    public StatementService(TransactionMapper transactions){
        this.transactions=transactions;
    }
    public StatementVO statement(String userId,String month){
        YearMonth period;
        try{
            period=YearMonth.parse(month);
        }
        catch(Exception e){
            throw new BankException("VALIDATION_ERROR","月份格式应为 YYYY-MM");
        }
        var entries=transactions.selectPeriod(userId,period.atDay(1),period.plusMonths(1).atDay(1)).stream() .map(r->new TransactionVO(r.getId(),r.getMerchant(),r.getCategory(),r.getAmountMinor(),r.getBookedOn())).toList();
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
                if(-amount>=200000)anomalies.add(entry);
            }
            else income+=amount;
        }
        return new StatementVO(month,expense,income,internal,categories,entries,anomalies, "单笔支出达到 2000 元，仅为候选，不能推断欺诈；本人账户内部转账不计收入支出",false);
    }
    public List<RecurringPaymentVO> recurring(String userId){
        var result=new ArrayList<RecurringPaymentVO>();
        for(var r:transactions.selectRecurring(userId)){
            double average=(double)ChronoUnit.DAYS.between(r.firstSeen(),r.lastSeen())/(r.occurrences()-1);
            if(average>=25&&average<=35)result.add(new RecurringPaymentVO(r.merchant(),r.occurrences(),r.firstSeen(),r.lastSeen(),r.minAmountMinor(),r.maxAmountMinor(),"周期扣费候选",false));
        }
        return result;
    }
}
