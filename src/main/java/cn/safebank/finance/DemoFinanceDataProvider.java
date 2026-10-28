package cn.safebank.finance;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;

@Component
public class DemoFinanceDataProvider implements FinanceDataProvider {
    private static final FinanceCatalog CATALOG = new FinanceCatalog(
            "DEMO", "SafeBank 本地合成产品库", "demo-finance-v1",
            LocalDate.of(2026, 9, 30), LocalDate.of(2025, 9, 30), LocalDate.of(2026, 9, 30),
            "产品、风险分层与指标均为虚构示例，不代表真实在售产品或 iFinD 数据。收益与回撤采用同一示例区间；存款未提供历史指标。",
            List.of(
                new FinanceProduct("DEMO-CASH", "灵活备用金 · 示例活期存款", "活期存款", 1, "较低风险",
                    100, 1, 0, 0, true, null, null, null, "随时支取；具体到账与渠道限额以银行为准",
                    "适合优先安排日常备用金，保持资金可用。", "存款仍有银行信用风险；存款保险保障受法定限额与条件约束。"),
                new FinanceProduct("DEMO-MONEY", "零钱管理 · 示例货币基金", "货币基金", 1, "较低波动",
                    100, 1, 0, 1, false, 136, 4, 25, "一般 T+1 交易日到账；节假日顺延，不承诺快速赎回",
                    "以短期货币市场工具为主要投资方向，适合关注流动性的资金。", "不保本，可能亏损；大额赎回、节假日及市场异常可能延迟到账。"),
                new FinanceProduct("DEMO-DEPOSIT", "定期积累 · 示例三个月存款", "定期存款", 1, "较低风险",
                    5000, 3, 3, 0, true, null, null, null, "约定存期三个月；提前支取可能损失利息",
                    "适合有明确使用日期、能留足三个月的资金。", "存在银行信用风险；提前支取计息规则、存款保险限额须向银行核实。"),
                new FinanceProduct("DEMO-SHORT", "稳步积累 · 示例短债基金 A", "短债基金", 2, "中低波动",
                    10000, 6, 0, 2, false, 288, 85, 40, "一般 T+2 交易日到账；短期持有可能收取较高赎回费",
                    "主要投资短久期债券，可作为中期资金研究方向。", "不保本；受利率、信用和流动性风险影响，短期可能亏损。"),
                new FinanceProduct("DEMO-BOND", "中期规划 · 示例纯债基金 A", "纯债基金", 2, "中低波动",
                    10000, 12, 0, 2, false, 344, 230, 55, "一般 T+2 交易日到账；实际规则以基金合同为准",
                    "主要投资债券，适合能留出较长持有时间的研究场景。", "不保本；久期变化及信用事件可能导致明显净值回撤。"),
                new FinanceProduct("DEMO-BALANCED", "多元配置 · 示例混合基金 A", "混合基金", 3, "中等波动",
                    10000, 36, 0, 2, false, 568, 1180, 100, "一般 T+2 交易日到账；不适合近期刚性支出",
                    "以股债组合分散资产类别，适合长期配置研究。", "不保本；股债可能同时下跌，分散配置不能消除损失。"),
                new FinanceProduct("DEMO-INDEX", "长期成长 · 示例宽基指数基金 A", "指数基金", 4, "较高波动",
                    10000, 36, 0, 2, false, 912, 2240, 60, "一般 T+2 交易日到账；应预留长期持有空间",
                    "跟踪宽基股票指数，适合愿意承担较大波动的长期研究。", "不保本；可能大幅亏损，历史上涨或回撤不代表未来表现或最大损失。")
            ));

    @Override public FinanceCatalog catalog() { return CATALOG; }
}
