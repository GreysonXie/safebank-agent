package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record MandatePreviewRequestDTO(@NotBlank String mandateId) {
}
