package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record CardConfirmDTO(@NotBlank String payloadHash, boolean confirmed) {}
