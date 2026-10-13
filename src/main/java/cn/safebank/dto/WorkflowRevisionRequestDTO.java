package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record WorkflowRevisionRequestDTO(@Positive long version,@NotNull @jakarta.validation.Valid WorkflowRequestDTO request) {
}
