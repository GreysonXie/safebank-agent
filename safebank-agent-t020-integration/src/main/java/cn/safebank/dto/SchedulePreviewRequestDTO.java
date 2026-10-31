package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record SchedulePreviewRequestDTO(@NotBlank String sourceId,@NotBlank String recipientId,@Positive long amountMinor,@NotBlank String firstDate,@NotBlank String endDate,@Min(1) @Max(31) int dayOfMonth) {
}
