package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record RecurringPaymentVO(String merchant, long occurrences, @JsonProperty("first_seen") java.time.LocalDate firstSeen, @JsonProperty("last_seen") java.time.LocalDate lastSeen, @JsonProperty("min_amount_minor") long minAmountMinor, @JsonProperty("max_amount_minor") long maxAmountMinor, String classification, @JsonProperty("usage_known") boolean usageKnown) {
}
