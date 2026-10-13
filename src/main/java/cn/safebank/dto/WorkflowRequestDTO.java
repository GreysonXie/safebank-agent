package cn.safebank.dto;
import jakarta.validation.constraints.*;
import java.util.List;
public record WorkflowRequestDTO(@NotBlank String month,@NotEmpty @Size(max=10) List<@NotBlank String> mandateIds, @NotBlank String sourceId,@NotBlank String recipientId,@NotBlank String firstDate,@NotBlank String endDate, @Min(1) @Max(28) int dayOfMonth,@AssertTrue boolean onlyConfirmedSavings) {
    public WorkflowRequestDTO{
        mandateIds=mandateIds==null?List.of():List.copyOf(mandateIds);
    }
}
