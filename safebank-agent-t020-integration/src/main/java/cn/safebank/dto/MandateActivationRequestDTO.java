package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record MandateActivationRequestDTO(String mandateId,@NotBlank @Size(max=120) String merchant,
        @Positive @Max(1000000) long amountMinor,@NotBlank @Pattern(regexp="MONTHLY|QUARTERLY|YEARLY") String billingInterval,
        @NotBlank String renewalDate) {}
