package cn.safebank.dto;
import jakarta.validation.constraints.Min;
public record ContextResetRequestDTO(@Min(1) long expectedContextVersion) {}
