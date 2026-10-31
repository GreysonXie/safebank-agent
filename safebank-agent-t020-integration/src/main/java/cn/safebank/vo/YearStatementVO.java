package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
/**
 * 年度账单：每月一行，金额口径与 {@link StatementVO} 的单月结果完全一致（共用同一段聚合代码）。
 * coverage 只描述本轮实际取到的记录范围，不代表数据完整。
 */
public record YearStatementVO(String year,
    @JsonProperty("expense_minor") long expenseMinor,
    @JsonProperty("income_minor") long incomeMinor,
    @JsonProperty("internal_transfer_minor") long internalTransferMinor,
    Map<String,Long> categories,
    List<MonthRow> months,
    @JsonProperty("record_count") int recordCount,
    Coverage coverage) {
    public record MonthRow(String month,
        @JsonProperty("expense_minor") long expenseMinor,
        @JsonProperty("income_minor") long incomeMinor,
        @JsonProperty("internal_transfer_minor") long internalTransferMinor,
        @JsonProperty("transaction_count") int transactionCount) {}
    public record Coverage(@JsonProperty("business_date") String businessDate,
        @JsonProperty("year_in_progress") boolean yearInProgress,
        @JsonProperty("first_booked_on") String firstBookedOn,
        @JsonProperty("last_booked_on") String lastBookedOn,
        @JsonProperty("beyond_business_date") boolean beyondBusinessDate) {}
}
