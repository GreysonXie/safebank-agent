package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record StatementVO(String month, @JsonProperty("expense_minor") long expenseMinor, @JsonProperty("income_minor") long incomeMinor, @JsonProperty("internal_transfer_minor") long internalTransferMinor, java.util.Map<String,Long> categories, java.util.List<TransactionVO> transactions, @JsonProperty("anomaly_candidates") java.util.List<TransactionVO> anomalyCandidates, @JsonProperty("anomaly_rule") String anomalyRule, @JsonProperty("usage_data_available") boolean usageDataAvailable) {
}
