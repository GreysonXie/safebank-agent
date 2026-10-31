package cn.safebank.dto;

import jakarta.validation.constraints.*;

/** Preferences are supplied explicitly; this request never contains bank account data. */
public record FinanceRecommendationRequestDTO(
        @NotNull(message="请选择风险偏好") RiskPreference riskPreference,
        @NotNull(message="请选择资金用途") Purpose purpose,
        @NotNull(message="请选择流动性需求") Liquidity liquidity,
        @NotNull(message="请填写可投入金额") @Min(value=100, message="金额至少为 1 元")
        @Max(value=10000000000L, message="金额最多为 1 亿元") Long amountMinor,
        @NotNull(message="请填写持有期限") @Min(value=1, message="期限至少为 1 个月")
        @Max(value=120, message="期限最多为 120 个月") Integer horizonMonths,
        @NotNull(message="请选择本金偏好") Boolean capitalProtection) {
    public enum RiskPreference { CONSERVATIVE, BALANCED, GROWTH }
    public enum Purpose { RESERVE, PLANNED, GROWTH }
    public enum Liquidity { IMMEDIATE, FLEXIBLE, LOCKABLE }
}
