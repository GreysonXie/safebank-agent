package cn.safebank.dto;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
public record AgentToolRequestDTO(@Pattern(regexp="[a-f0-9]{64}") @NotBlank String operationKey,@Pattern(regexp="[a-f0-9]{64}") @NotBlank String inputHash,@NotBlank @Size(max=80) String tool, @NotNull JsonNode args) {}
