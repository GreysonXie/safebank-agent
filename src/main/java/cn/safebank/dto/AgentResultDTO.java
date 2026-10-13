package cn.safebank.dto;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;
public record AgentResultDTO(@NotBlank @Size(max=80) String turnId,@Min(1) long inputVersion,@NotNull JsonNode answer,@NotNull @Size(max=8) List<String> evidenceIds) {}
