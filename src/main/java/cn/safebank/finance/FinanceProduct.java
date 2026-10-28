package cn.safebank.finance;

/** Basis points represent percentages; money is always expressed in integer fen. */
public record FinanceProduct(
        String id, String name, String category, int riskLevel, String riskLabel,
        long minimumAmountMinor, int minimumHorizonMonths, int lockMonths,
        int redemptionBusinessDays, boolean capitalProtected, Integer periodReturnBps,
        Integer maxDrawdownBps, Integer annualFeeBps, String liquidityNote,
        String description, String riskNote) {
}
