package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record WorkflowVersionRequestDTO(@Positive long version) {
}
