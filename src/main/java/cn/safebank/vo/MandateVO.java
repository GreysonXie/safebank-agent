package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record MandateVO(String id, @JsonProperty("user_id") String userId, String merchant, @JsonProperty("amount_minor") Long amountMinor, @JsonProperty("renewal_date") java.time.LocalDate renewalDate, String status, @JsonProperty("contract_status") String contractStatus, @JsonProperty("billing_interval") String billingInterval,
    /** 以银行授权状态为主、日期关注程度为辅的固定分组：已撤销 / 状态未确认 / 临近（7天内）/ 远期 / 记录日期已过待核对 / 日期未知。 */
    String attention,
    @JsonProperty("days_to_renewal") Integer daysToRenewal) {
}
