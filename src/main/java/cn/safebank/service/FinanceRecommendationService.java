package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.dto.FinanceRecommendationRequestDTO;
import cn.safebank.finance.*;
import cn.safebank.vo.FinanceRecommendationVO;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import static cn.safebank.dto.FinanceRecommendationRequestDTO.*;

/** Explicit eligibility rules precede ordering. This service has no banking or model tools. */
@Service
public class FinanceRecommendationService {
    private final FinanceDataProvider data;
    private final Clock clock;

    public FinanceRecommendationService(FinanceDataProvider data, Clock clock) {
        this.data = data;
        this.clock = clock;
    }

    public FinanceRecommendationVO recommend(FinanceRecommendationRequestDTO request) {
        validate(request);
        FinanceCatalog catalog = data.catalog();
        int riskCeiling = switch (request.riskPreference()) {
            case CONSERVATIVE -> 2;
            case BALANCED -> 3;
            case GROWTH -> 4;
        };
        if (request.purpose() == Purpose.RESERVE) riskCeiling = 1;
        var candidates = new ArrayList<FinanceRecommendationVO.Candidate>();
        var exclusions = new ArrayList<FinanceRecommendationVO.Exclusion>();
        for (FinanceProduct product : catalog.products()) {
            var excluded = new ArrayList<String>();
            if (product.riskLevel() > riskCeiling) excluded.add("波动层级超出当前风险偏好或备用金用途");
            if (request.capitalProtection() && !product.capitalProtected()) excluded.add("不符合本金保障偏好");
            if (request.amountMinor() < product.minimumAmountMinor()) excluded.add("低于示例起投金额");
            if (request.horizonMonths() < product.minimumHorizonMonths()) excluded.add("持有期限短于该方向建议期限");
            if (request.horizonMonths() < product.lockMonths()) excluded.add("使用日期早于约定存期结束");
            if (request.liquidity() == Liquidity.IMMEDIATE &&
                    (product.redemptionBusinessDays() > 0 || product.lockMonths() > 0))
                excluded.add("需要随时支取，不能等待赎回或存期");
            if (request.liquidity() == Liquidity.FLEXIBLE && product.lockMonths() > 0)
                excluded.add("需要灵活取用，不适合约定存期");
            if (request.purpose() == Purpose.RESERVE && product.lockMonths() > 0)
                excluded.add("备用金需要保持流动性");
            if (!excluded.isEmpty()) {
                exclusions.add(new FinanceRecommendationVO.Exclusion(product.id(), product.name(), List.copyOf(excluded)));
                continue;
            }
            var reasons = new ArrayList<String>();
            reasons.add(request.purpose() == Purpose.RESERVE ? "备用金用途：优先保留流动性与较低波动" :
                    request.purpose() == Purpose.PLANNED ? "计划支出用途：建议期限未超过你的资金使用时间" : "长期配置用途：结合期限与承受波动的意愿筛选");
            reasons.add("你的持有期限为 " + request.horizonMonths() + " 个月，该方向建议至少 " + product.minimumHorizonMonths() + " 个月");
            reasons.add(request.capitalProtection() ? "符合本金保障偏好，保障范围与条件仍须核实" :
                    "示例波动层级处于所选范围内；这不等同于正式风险测评");
            candidates.add(new FinanceRecommendationVO.Candidate(product, List.copyOf(reasons)));
        }
        candidates.sort(Comparator.comparingInt((FinanceRecommendationVO.Candidate c) -> priority(c.product(), request))
                .thenComparing(c -> c.product().id()));
        String summary = candidates.isEmpty() ? "当前条件下没有匹配方向，可调整期限、流动性或金额后重新筛选。" :
                "在 " + catalog.products().size() + " 个示例方向中找到 " + candidates.size() + " 个匹配方向；按用途与波动偏好排序。";
        return new FinanceRecommendationVO(catalog.mode(), catalog.source(), catalog.version(), catalog.asOf(),
                catalog.periodStart(), catalog.periodEnd(), OffsetDateTime.now(clock), catalog.disclosure(), request,
                catalog.products().size(), summary, List.copyOf(candidates), List.copyOf(exclusions), List.of(
                    "先按本金偏好、波动层级、金额、期限和流动性排除不匹配方向；备用金仅保留较低波动且无约定存期的方向。",
                    "稳健偏好先展示较低波动方向；平衡及成长偏好在允许范围内优先展示相应波动层级，同层级按稳定编号排序。",
                    "收益与回撤只用于同区间事实比较，不参与排序，不预测未来收益或最大损失。",
                    "筛选仅覆盖本地示例库，不是全市场排名、正式风险测评或申购建议。"));
    }

    private int priority(FinanceProduct product, FinanceRecommendationRequestDTO request) {
        if (request.purpose() == Purpose.RESERVE || request.riskPreference() == RiskPreference.CONSERVATIVE)
            return product.riskLevel() * 10 + product.lockMonths();
        return -product.riskLevel() * 10 + product.lockMonths();
    }

    private void validate(FinanceRecommendationRequestDTO r) {
        if (r == null || r.riskPreference() == null || r.purpose() == null || r.liquidity() == null ||
                r.capitalProtection() == null || r.amountMinor() == null || r.horizonMonths() == null)
            throw new BankException("INVALID_PREFERENCES", "请完整填写推荐偏好");
        if (r.amountMinor() < 100 || r.amountMinor() > 10000000000L || r.horizonMonths() < 1 || r.horizonMonths() > 120)
            throw new BankException("INVALID_PREFERENCES", "金额须为 1 元至 1 亿元，期限须为 1 至 120 个月");
    }
}
