package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record TransferPreviewRequestDTO(@NotBlank String sourceId,@NotBlank String recipientId,@Positive long amountMinor) {
}
