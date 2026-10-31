package cn.safebank.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 账单通用查询信封（唯一形状）：任意区间 × 分桶粒度 × 拆分维度 × 对比 × 明细分页。
 * <p>金额口径与月报/年报同源（{@link cn.safebank.service.StatementRules}）：内部转账不计收支，
 * 退款计入入账；{@code buckets} 各桶指标之和必然等于 {@code totals}（守恒）。</p>
 */
public record StatementQueryVO(
    Query query,
    Metrics totals,
    List<Bucket> buckets,
    Breakdown breakdown,
    Comparison comparison,
    @JsonProperty("anomaly_candidates") List<Row> anomalyCandidates,
    Rows rows,
    Coverage coverage,
    List<String> notes) {

    /** 归一回显：模型/界面看到的永远是服务端实际执行的区间与粒度，不是请求原文。 */
    public record Query(String start, String end, String bucket,
        @JsonProperty("group_by") String groupBy, Filter filter, String compare,
        @JsonProperty("top_n") int topN) {}

    public record Filter(String direction, List<String> categories, List<String> merchants,
        List<String> accounts,
        @JsonProperty("min_amount_minor") Long minAmountMinor,
        @JsonProperty("max_amount_minor") Long maxAmountMinor,
        @JsonProperty("exclude_internal") boolean excludeInternal) {}

    public record Metrics(@JsonProperty("expense_minor") long expenseMinor,
        @JsonProperty("income_minor") long incomeMinor,
        @JsonProperty("net_minor") long netMinor,
        @JsonProperty("internal_transfer_minor") long internalTransferMinor,
        @JsonProperty("transaction_count") int transactionCount) {}

    public record Bucket(String key, String label, String start, String end, Metrics metrics) {}

    public record Breakdown(String dimension, String measure, List<Group> groups,
        boolean truncated,
        @JsonProperty("rest_amount_minor") long restAmountMinor,
        @JsonProperty("rest_count") int restCount) {}

    public record Group(String key, String label, Metrics metrics,
        @JsonProperty("amount_minor") long amountMinor,
        @JsonProperty("share_percent") double sharePercent) {}

    public record Comparison(String mode,
        @JsonProperty("baseline_start") String baselineStart,
        @JsonProperty("baseline_end") String baselineEnd,
        Metrics baseline, Metrics delta, List<ComparisonGroup> groups,
        @JsonProperty("baseline_empty") boolean baselineEmpty) {}

    public record ComparisonGroup(String key, String label,
        @JsonProperty("current_minor") long currentMinor,
        @JsonProperty("baseline_minor") long baselineMinor,
        @JsonProperty("difference_minor") long differenceMinor,
        @JsonProperty("difference_magnitude_minor") long differenceMagnitudeMinor,
        String direction,
        @JsonProperty("current_count") int currentCount,
        @JsonProperty("baseline_count") int baselineCount) {}

    public record Row(String id, String merchant, String category,
        @JsonProperty("account_id") String accountId,
        @JsonProperty("account_label") String accountLabel,
        @JsonProperty("amount_minor") long amountMinor,
        @JsonProperty("booked_on") String bookedOn) {}

    public record Rows(@JsonProperty("total_count") int totalCount, int offset, int limit,
        boolean truncated, List<Row> items) {}

    public record Coverage(@JsonProperty("business_date") String businessDate,
        @JsonProperty("first_booked_on") String firstBookedOn,
        @JsonProperty("last_booked_on") String lastBookedOn,
        @JsonProperty("beyond_business_date") boolean beyondBusinessDate) {}
}
