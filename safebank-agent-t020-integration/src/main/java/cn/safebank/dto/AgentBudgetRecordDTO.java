package cn.safebank.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;
public record AgentBudgetRecordDTO(@NotBlank @Size(max=80) String reservationId,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String operationKey,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String inputHash,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String javaTurnId,@NotBlank @Pattern(regexp="plan|draft|repair") String purpose,@NotNull Boolean success,JsonNode usage) {}
