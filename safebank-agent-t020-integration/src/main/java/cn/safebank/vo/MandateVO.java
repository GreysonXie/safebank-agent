package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record MandateVO(String id, @JsonProperty("user_id") String userId, String merchant, @JsonProperty("amount_minor") Long amountMinor, @JsonProperty("renewal_date") java.time.LocalDate renewalDate, String status, @JsonProperty("contract_status") String contractStatus, @JsonProperty("billing_interval") String billingInterval) {
}
