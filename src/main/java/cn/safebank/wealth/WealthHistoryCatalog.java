package cn.safebank.wealth;
import cn.safebank.BankException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.math.*;
import java.time.*;
import java.util.*;
/**
 * 理财历史净值样本：唯一数据源为 classpath 上的 products-history-v1.json（工作日样本，未核实节假日）。
 * 只读、确定性生成（算法/种子/舍入写在文件里）；文件缺失或坏数据的查询局部不可用，不影响应用启动。
 */
@Component public class WealthHistoryCatalog {
    private final String datasetId;
    private final String datasetVersion;
    private final LocalDate dataThrough;   // 数据集覆盖到哪一天（样本批次自身的日期，不等于"今天"）
    private final LocalDate today;         // 系统业务日期，随真实日期走（上海时区）
    private final String coverageNote;
    private final String catalogDatasetId;
    private final Map<String,List<Map<String,Object>>> series=new LinkedHashMap<>();
    public WealthHistoryCatalog(ObjectMapper json,java.time.Clock clock){
        today=cn.safebank.common.SimulationClock.businessDate(clock);
        JsonNode source=deviceOrNull(json);
        if(source==null){datasetId=null;datasetVersion=null;dataThrough=null;coverageNote="历史样本不可用";catalogDatasetId=null;return;}
        datasetId=source.path("dataset_id").asText();
        datasetVersion=source.path("dataset_version").asText();
        dataThrough=LocalDate.parse(source.path("business_date").asText());
        coverageNote=source.path("coverage_note").asText();
        catalogDatasetId=source.path("catalog_dataset_id").asText();
        for(var product:source.path("products")){
            var rows=new ArrayList<Map<String,Object>>();
            for(var row:product.path("series"))rows.add(Map.of("date",row.path("date").asText(),"nav",row.path("nav").asText()));
            series.put(product.path("product_id").asText(),List.copyOf(rows));
        }
    }
    private JsonNode deviceOrNull(ObjectMapper json){
        try(InputStream stream=getClass().getResourceAsStream("/wealth/products-history-v1.json")){
            if(stream==null)return null;
            return json.readTree(stream);
        }catch(Exception error){return null;}
    }
    public boolean available(){return datasetId!=null;}
    public String datasetVersion(){return datasetVersion;}
    public String catalogDatasetId(){return catalogDatasetId;}
    /** 历史样本覆盖到的最后一天（批次自带日期，用于"数据截至"说明）。 */
    public LocalDate businessDate(){return dataThrough;}
    /** 系统业务日期：随真实日期（上海时区）取当天，用于月份说法解析与"未来月份"判断。 */
    public LocalDate today(){return today;}
    public String coverageNote(){return coverageNote;}
    public Set<String> products(){return series.keySet();}
    public List<Map<String,Object>> series(String productId){return series.getOrDefault(productId,List.of());}
    /** 区间查询：至少两条不同日期的有效样本，端点取区间内首末已记录样本。 */
    public WealthHistoryVO query(String productId,String from,String to,String displayName){
        if(!available())throw new BankException("HISTORY_UNAVAILABLE","历史样本暂不可用，请稍后再试");
        LocalDate start=date(from),end=date(to);
        if(start.isAfter(end))throw new BankException("VALIDATION_ERROR","开始日期不能晚于结束日期");
        if(end.isAfter(today))throw new BankException("VALIDATION_ERROR","不能查询业务日期之后的记录（业务日期 "+today+"，历史数据截至 "+(dataThrough==null?"未知":dataThrough)+"）");
        var rows=series(productId);
        if(rows.isEmpty())throw new BankException("NOT_FOUND","该产品没有历史样本："+productId);
        var picked=new ArrayList<Map<String,Object>>();
        for(var row:rows){
            LocalDate day=LocalDate.parse(String.valueOf(row.get("date")));
            if(!day.isBefore(start)&&!day.isAfter(end))picked.add(row);
        }
        if(picked.size()<2)throw new BankException("SAMPLE_INSUFFICIENT","该区间有效样本少于两条，无法计算区间变化");
        String actualFrom=String.valueOf(picked.get(0).get("date")),actualTo=String.valueOf(picked.get(picked.size()-1).get("date"));
        BigDecimal navFrom=new BigDecimal(String.valueOf(picked.get(0).get("nav"))),navTo=new BigDecimal(String.valueOf(picked.get(picked.size()-1).get("nav")));
        BigDecimal change=navTo.divide(navFrom,10,RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2,RoundingMode.HALF_UP);
        var drawdown=drawdown(picked);
        return new WealthHistoryVO(productId,displayName,from,to,actualFrom,actualTo,navFrom.toPlainString(),navTo.toPlainString(),
            change,picked.size(),datasetVersion,catalogDatasetId,
            drawdown.percent(),drawdown.peakDate(),drawdown.peakNav(),drawdown.troughDate(),drawdown.troughNav(),
            drawdown.percent().signum()==0?"该样本区间未出现回撤":"回撤按区间内峰值到其后最低点计算，不含区间外的历史峰值",
            coverageNote,List.copyOf(picked));
    }
    /** 把客户的月份说法解析成实际区间：YYYY-MM、YYYY-M、M（当年或上一年）、上月/上个月/本月/这个月/最近。
     *  解析以系统业务日期（今天）为准，不用样本批次日期；样本只覆盖到哪天由"数据截至"说明。 */
    public String[] resolveMonth(String token){
        if(!available())throw new BankException("HISTORY_UNAVAILABLE","历史样本暂不可用，请稍后再试");
        String value=token==null?"":token.trim();
        YearMonth period=switch(value){
            case "近30天","最近30天","过去30天","近一个月","最近一个月","过去一个月" -> null;
            case "上月","上个月" -> YearMonth.from(today).minusMonths(1);
            case "本月","这个月","最近" -> YearMonth.from(today);
            default -> parseMonth(value);
        };
        if(period==null){LocalDate rolling=today.minusDays(29);return new String[]{rolling.toString(),today.toString()};}
        LocalDate start=period.atDay(1);
        LocalDate end=period.atEndOfMonth();
        if(start.isAfter(today))throw new BankException("VALIDATION_ERROR","该月份还没有记录（业务日期 "+today+"，历史数据截至 "+(dataThrough==null?"未知":dataThrough)+"）");
        if(end.isAfter(today))end=today;
        return new String[]{start.toString(),end.toString()};
    }
    private YearMonth parseMonth(String value){
        if(value.matches("20[0-9]{2}-(0[1-9]|1[0-2])")||value.matches("20[0-9]{2}-(0?[1-9]|1[0-2])")){
            String[] parts=value.split("-");return YearMonth.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));
        }
        if(value.matches("(0?[1-9]|1[0-2])")){
            int month=Integer.parseInt(value);YearMonth period=YearMonth.of(today.getYear(),month);
            return period.atDay(1).isAfter(today)?period.minusYears(1):period;
        }
        throw new BankException("VALIDATION_ERROR","月份请用 YYYY-MM 或 1-12 表示（也可说上月/本月/最近）");
    }    /** 最大回撤：按样本顺序维护截至当日的峰值，取 (峰值-当日净值)/峰值 的最大值；并列取最早一组。 */
    public record Drawdown(BigDecimal percent,String peakDate,String peakNav,String troughDate,String troughNav) {}
    public static Drawdown drawdown(List<Map<String,Object>> samples){
        if(samples==null||samples.size()<2)throw new BankException("SAMPLE_INSUFFICIENT","该区间有效样本少于两条，无法计算最大回撤");
        BigDecimal peak=null,peakValue=null,troughValue=null,best=BigDecimal.ZERO;
        String peakDate=null,troughDate=null,bestPeakDate=null,bestPeakNav=null,bestTroughDate=null,bestTroughNav=null;
        for(var row:samples){
            BigDecimal nav=new BigDecimal(String.valueOf(row.get("nav")));
            if(nav.signum()<=0)throw new BankException("VALIDATION_ERROR","净值样本不是正值，无法计算最大回撤");
            String date=String.valueOf(row.get("date"));
            if(peak==null||nav.compareTo(peak)>0){peak=nav;peakValue=nav;peakDate=date;}
            BigDecimal drop=peak.subtract(nav).multiply(BigDecimal.valueOf(100)).divide(peak,10,RoundingMode.HALF_UP);
            if(drop.compareTo(best)>0){best=drop;bestPeakDate=peakDate;bestPeakNav=peakValue.toPlainString();bestTroughDate=date;bestTroughNav=nav.toPlainString();troughValue=nav;troughDate=date;}
        }
        return new Drawdown(best.setScale(2,RoundingMode.HALF_UP),bestPeakDate,bestPeakNav,bestTroughDate,bestTroughNav);
    }    private LocalDate date(String value){
        try{return LocalDate.parse(value);}catch(Exception invalid){throw new BankException("VALIDATION_ERROR","日期格式应为 YYYY-MM-DD");}
    }
}
