package cn.safebank.vo;
import com.fasterxml.jackson.annotation.*;
public record RevokeParametersVO(@JsonProperty("mandate_id") String mandateId,String merchant,@JsonProperty("charge_amount_minor") long chargeAmountMinor, @JsonProperty("billing_interval") String billingInterval,@JsonProperty("estimated_monthly_saving_minor") @JsonInclude(JsonInclude.Include.NON_NULL) Long estimatedMonthlySavingMinor, @JsonProperty("contract_status") String contractStatus,String scope) implements ActionParametersVO {
}
