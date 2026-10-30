package cn.safebank;

import cn.safebank.vo.StatementFilterVO;
import cn.safebank.wealth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** T015：理财历史样本的区间端点、程序计算、版本绑定与跨区间/跨产品引用拒绝。 */
class WealthHistoryTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final WealthCatalog catalog=new WealthCatalog(json);
    /** 固定业务日 2026-10-31（上海时区），样本批次只覆盖到 2026-10-30：两者必须分开。 */
    private static final java.time.Clock CLOCK=java.time.Clock.fixed(java.time.Instant.parse("2026-10-30T18:30:00Z"),java.time.ZoneOffset.UTC);
    private final WealthHistoryCatalog history=new WealthHistoryCatalog(json,CLOCK);
    /** 随机真实时钟：月份说法必须解析到"今天"所在的月份，而不是样本批次日期。 */
    private final WealthHistoryCatalog liveHistory=new WealthHistoryCatalog(json,java.time.Clock.systemDefaultZone());

    private String display(String id){return catalog.product(id).path("display_name").asText(id);}

    @Test void monthWordsFollowRealTodayNotDatasetDate(){
        var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var month=java.time.YearMonth.from(today);
        assertEquals(today,liveHistory.today(),"系统业务日期取真实今天");
        var current=liveHistory.resolveMonth("本月");
        assertEquals(month.atDay(1).toString(),current[0]);
        assertEquals(today.toString(),current[1],"本月截至今天，而不是数据集那天的月内位置");
        var previous=liveHistory.resolveMonth("上月");
        assertEquals(month.minusMonths(1).atDay(1).toString(),previous[0]);
        assertEquals(month.minusMonths(1).atEndOfMonth().toString(),previous[1],"整月已过去的月份取满月");
        // 数据集只覆盖到 10-30：业务日期之后的新月份必须明确说"还没有记录"，而不是错标成上月
        var later=java.time.YearMonth.from(today).plusMonths(1);
        var error=assertThrows(BankException.class,()->liveHistory.resolveMonth(later.toString()));
        assertEquals("VALIDATION_ERROR",error.code);
        assertTrue(error.getMessage().contains(today.toString()),"提示里的业务日期是今天");
    }

    @Test void expiredMonthWindowIsJudgedByTodayEvenWhenSamplesStopEarlier(){
        assertEquals(java.time.LocalDate.of(2026,10,31),history.today());
        assertEquals(java.time.LocalDate.of(2026,10,30),history.businessDate(),"businessDate 仍是样本覆盖最后一天");
        assertEquals(List.of("2026-10-01","2026-10-31"),List.of(history.resolveMonth("本月")),"本月按今天截断");
        var october=history.query("DEMO-WM-02",history.resolveMonth("本月")[0],history.resolveMonth("本月")[1],display("DEMO-WM-02"));
        assertEquals("2026-10-30",october.actualTo(),"实际端点落在最后一条样本");
    }

    @Test void samplesExistAsWorkdaySeriesWithVersionAndReproducibleRules(){
        assertTrue(history.available());
        assertEquals("1",history.datasetVersion());
        assertEquals("safebank-wealth-demo-v1",history.catalogDatasetId());
        assertEquals(java.time.LocalDate.of(2026,10,30),history.businessDate());
        assertEquals(Set.of("DEMO-WM-02","DEMO-WM-03"),history.products());
        for(var product:history.products()){
            var rows=history.series(product);
            assertTrue(rows.size()>=30);
            var dates=new HashSet<String>();
            for(var row:rows){
                var date=String.valueOf(row.get("date"));
                assertTrue(dates.add(date),"日期唯一");
                var nav=new BigDecimal(String.valueOf(row.get("nav")));
                assertTrue(nav.signum()>0,"净值恒正");
                assertEquals(4,nav.scale(),"净值保留 4 位");
            }
        }
    }

    @Test void changeIsComputedFromStoredEndpointsIncludingNegativeDirection(){
        var september=history.query("DEMO-WM-02","2026-09-01","2026-09-30",display("DEMO-WM-02"));
        assertEquals("2026-09-01",september.actualFrom());
        assertEquals("2026-09-30",september.actualTo());
        assertEquals(22,september.records(),"9 月工作日样本");
        BigDecimal expected=new BigDecimal(september.navTo()).divide(new BigDecimal(september.navFrom()),10,java.math.RoundingMode.HALF_UP)
            .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2,java.math.RoundingMode.HALF_UP);
        assertEquals(expected,september.changePercent(),"变化由落盘 4 位净值算出");
        var october=history.query("DEMO-WM-03","2026-10-01","2026-10-30",display("DEMO-WM-03"));
        assertEquals("2026-10-30",october.actualTo(),"10 月截至业务日期");
        BigDecimal octoberExpected=new BigDecimal(october.navTo()).divide(new BigDecimal(october.navFrom()),10,java.math.RoundingMode.HALF_UP)
            .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2,java.math.RoundingMode.HALF_UP);
        assertEquals(octoberExpected,october.changePercent(),"方向与幅度按实际样本计算，不按风险等级排高低");
        assertNotNull(october.coverageNote());assertTrue(october.coverageNote().contains("工作日样本"));
    }

    @Test void naturalMonthTokensResolveToBusinessDateBoundedRanges(){
        assertEquals(List.of("2026-09-01","2026-09-30"),List.of(history.resolveMonth("2026-09")));
        assertEquals(List.of("2026-09-01","2026-09-30"),List.of(history.resolveMonth("9")),"只给月份时按当年解析");
        assertEquals(List.of("2026-09-01","2026-09-30"),List.of(history.resolveMonth("09")));
        assertEquals(List.of("2026-10-01","2026-10-31"),List.of(history.resolveMonth("本月")),"本月按今天截断，不是按样本日期");
        assertEquals(List.of("2026-10-01","2026-10-31"),List.of(history.resolveMonth("最近")));
        assertEquals(List.of("2026-09-01","2026-09-30"),List.of(history.resolveMonth("上月")));
        assertEquals(List.of("2026-09-01","2026-09-30"),List.of(history.resolveMonth("上个月")));
        assertEquals(List.of("2026-10-02","2026-10-31"),List.of(history.resolveMonth("近30天")),"滚动窗口从今天回推");
        assertThrows(BankException.class,()->history.resolveMonth("2026-11"),"未来月份拒绝");
        assertThrows(BankException.class,()->history.resolveMonth("下个月"),"不支持的说法要澄清");
        assertThrows(BankException.class,()->history.resolveMonth("13"),"非法月份拒绝");
        var pickup=history.query("DEMO-WM-02",history.resolveMonth("9")[0],history.resolveMonth("9")[1],display("DEMO-WM-02"));
        assertEquals("2026-09-01",pickup.actualFrom());assertEquals(22,pickup.records());
    }
    @Test void maxDrawdownMatchesHandComputedSampleAndTieTakesEarliest(){
        var samples=List.of(
            Map.<String,Object>of("date","2026-09-01","nav","1.0000"),
            Map.<String,Object>of("date","2026-09-02","nav","1.1000"),
            Map.<String,Object>of("date","2026-09-03","nav","0.9900"),
            Map.<String,Object>of("date","2026-09-04","nav","1.2000"),
            Map.<String,Object>of("date","2026-09-05","nav","1.0800"));
        var result=WealthHistoryCatalog.drawdown(samples);
        assertEquals(new BigDecimal("10.00"),result.percent(),"峰值 1.10 到 0.99 为 10%");
        assertEquals("2026-09-02",result.peakDate());assertEquals("1.1000",result.peakNav());
        assertEquals("2026-09-03",result.troughDate());assertEquals("0.9900",result.troughNav());
        var ties=List.of(
            Map.<String,Object>of("date","2026-09-01","nav","1.0000"),
            Map.<String,Object>of("date","2026-09-02","nav","1.2000"),
            Map.<String,Object>of("date","2026-09-03","nav","1.0800"),
            Map.<String,Object>of("date","2026-09-04","nav","1.3000"),
            Map.<String,Object>of("date","2026-09-05","nav","1.1700"));
        var earliest=WealthHistoryCatalog.drawdown(ties);
        assertEquals(new BigDecimal("10.00"),earliest.percent());
        assertEquals("2026-09-02",earliest.peakDate(),"并列时取最早发生的一组");
        assertEquals("2026-09-03",earliest.troughDate());
        var rising=List.of(Map.<String,Object>of("date","2026-09-01","nav","1.0000"),Map.<String,Object>of("date","2026-09-02","nav","1.0100"),Map.<String,Object>of("date","2026-09-03","nav","1.0200"));
        assertEquals(0,WealthHistoryCatalog.drawdown(rising).percent().signum(),"单调上涨没有回撤");
        assertNull(WealthHistoryCatalog.drawdown(rising).peakDate(),"没有回撤时不编峰谷");
        var falling=List.of(Map.<String,Object>of("date","2026-09-01","nav","1.2000"),Map.<String,Object>of("date","2026-09-02","nav","1.0800"),Map.<String,Object>of("date","2026-09-03","nav","0.9900"));
        var drop=WealthHistoryCatalog.drawdown(falling);
        assertEquals(new BigDecimal("17.50"),drop.percent(),"单调下降按首日峰值计算 17.5%");
        assertEquals("2026-09-01",drop.peakDate());
        assertThrows(BankException.class,()->WealthHistoryCatalog.drawdown(List.of(Map.<String,Object>of("date","2026-09-01","nav","1.0000"))),"不足两条拒绝");
        assertThrows(BankException.class,()->WealthHistoryCatalog.drawdown(List.of(Map.<String,Object>of("date","2026-09-01","nav","1.0000"),Map.<String,Object>of("date","2026-09-02","nav","0.0000"))),"非正净值拒绝");
    }
    @Test void queryIncludesDrawdownAlongsideIntervalChange(){
        var september=history.query("DEMO-WM-02","2026-09-01","2026-09-30",display("DEMO-WM-02"));
        assertNotNull(september.maxDrawdownPercent());
        assertTrue(september.maxDrawdownPercent().signum()>=0);
        assertEquals(4,new BigDecimal(september.navFrom()).scale(),"净值仍不走分转元");
        assertEquals("2026-09-01",september.actualFrom());
        assertNotNull(september.drawdownNote());
    }
    @Test void insufficientSamplesAndIllegalRangesAreRejected(){
        assertThrows(BankException.class,()->history.query("DEMO-WM-02","2026-09-01","2026-09-01",display("DEMO-WM-02")),"只有一条样本时拒绝");
        assertThrows(BankException.class,()->history.query("DEMO-WM-02","2026-09-30","2026-09-01",display("DEMO-WM-02")),"起止倒置拒绝");
        var future=assertThrows(BankException.class,()->history.query("DEMO-WM-02","2026-10-01","2026-11-30",display("DEMO-WM-02")));
        assertTrue(future.getMessage().contains("业务日期"),"未来日期按契约拒绝");
        assertThrows(BankException.class,()->history.query("DEMO-WM-01","2026-09-01","2026-09-30","活钱管理"),"无历史样本的产品拒绝");
        assertThrows(BankException.class,()->history.query("DEMO-WM-02","2026-13-01","2026-09-30",display("DEMO-WM-02")),"非法日期拒绝");
    }

    @Test void factsCarryProductRangeAndVersionAndRejectCrossRangeOrCrossProduct(){
        var september02=history.query("DEMO-WM-02","2026-09-01","2026-09-30",display("DEMO-WM-02"));
        var october02=history.query("DEMO-WM-02","2026-10-01","2026-10-30",display("DEMO-WM-02"));
        var september03=history.query("DEMO-WM-03","2026-09-01","2026-09-30",display("DEMO-WM-03"));
        String prefix02="wealth_history_DEMO-WM-02_2026_09_01_2026_09_30_v1";
        String prefix03="wealth_history_DEMO-WM-03_2026_09_01_2026_09_30_v1";
        var both=new GroundedAnswer(json,List.of(
            new BankReplyComposer.Evidence("get_wealth_history",json.convertValue(september02,Map.class)),
            new BankReplyComposer.Evidence("get_wealth_history",json.convertValue(september03,Map.class))));
        var facts=both.factCatalog();
        assertTrue(facts.containsKey(prefix02+".change_percent"));assertTrue(facts.containsKey(prefix03+".change_percent"));
        assertEquals(september02.changePercent(),facts.get(prefix02+".change_percent").number());
        assertEquals(september03.changePercent(),facts.get(prefix03+".change_percent").number());
        String bothText="固收90天持有 9 月变化 {{"+prefix02+".change_percent}}，固收180天持有 {{"+prefix03+".change_percent}}。";
        both.render(json.valueToTree(Map.of("kind","analysis","answer",bothText)));
        assertThrows(BankException.class,()->both.render(json.valueToTree(Map.of("kind","analysis","answer",
            "固收90天持有 10 月变化 {{wealth_history_DEMO-WM-02_2026_10_01_2026_10_30_v1.change_percent}}。"))),
            "引用本轮未查询的区间（即使格式有效）被拒");
        var single=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_wealth_history",json.convertValue(october02,Map.class))));
        assertThrows(BankException.class,()->single.render(json.valueToTree(Map.of("kind","analysis","answer",
            "变化 {{"+prefix02+".change_percent}}。"))),"引用另一产品/另一区间的事实被拒");
        assertThrows(BankException.class,()->single.render(json.valueToTree(Map.of("kind","analysis","answer",
            "变化 {{wealth_history_DEMO-WM-02_2026_10_01_2026_10_30_v2.change_percent}}。"))),"不同数据版本不互通");
    }
}
