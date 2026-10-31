package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record MandateActivationParametersVO(@JsonProperty("mandate_id") String mandateId,
        String merchant,@JsonProperty("charge_amount_minor") long chargeAmountMinor,
        @JsonProperty("billing_interval") String billingInterval,@JsonProperty("renewal_date") String renewalDate,
        boolean reactivation,String scope) implements ActionParametersVO {}
