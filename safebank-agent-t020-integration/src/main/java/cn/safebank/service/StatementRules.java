package cn.safebank.service;

/**
 * 账单金额口径的唯一实现：月报、年报、组合筛选与通用查询都只从这里取规则。
 * <p>规则：本人账户内部转账不计收入支出（转出单列为内部转账，转入不参与收支）；
 * 退款计入入账，支出不减退款；单笔支出达到 2000 元仅作异常候选，不构成欺诈结论。</p>
 */
public final class StatementRules {
    public static final String INTERNAL_TRANSFER = "内部转账";
    public static final long ANOMALY_THRESHOLD_MINOR = 200_000L;
    public static final String ANOMALY_RULE = "单笔支出达到 2000 元，仅为候选，不能推断欺诈；本人账户内部转账不计收入支出";
    public static final String SCOPE_NOTE = "合计按全部匹配记录计算；明细可能因容量或隔离被省略，省略不代表没有记录";

    private StatementRules() {
    }

    /** 一笔记录在收支口径中的角色。金额为 0 或内部转入记为 NONE，不进任何收支合计。 */
    public enum Flow { EXPENSE, INCOME, INTERNAL, NONE }

    public static Flow flow(String category, long amountMinor) {
        if (INTERNAL_TRANSFER.equals(category)) return amountMinor < 0 ? Flow.INTERNAL : Flow.NONE;
        if (amountMinor < 0) return Flow.EXPENSE;
        if (amountMinor > 0) return Flow.INCOME;
        return Flow.NONE;
    }

    /** 计入该角色的金额绝对值；NONE 恒为 0。 */
    public static long amountOf(Flow flow, long amountMinor) {
        return switch (flow) {
            case EXPENSE, INCOME, INTERNAL -> Math.abs(amountMinor);
            case NONE -> 0L;
        };
    }

    public static boolean anomalyCandidate(String category, long amountMinor) {
        return flow(category, amountMinor) == Flow.EXPENSE && -amountMinor >= ANOMALY_THRESHOLD_MINOR;
    }

    /** 以“元”为单位的口径说明，供 notes 与答案引用。 */
    public static String yuan(long minor) {
        return String.format("%.2f", minor / 100.0);
    }
}
