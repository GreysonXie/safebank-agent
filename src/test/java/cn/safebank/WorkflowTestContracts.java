package cn.safebank;
import jakarta.validation.constraints.*;
import java.util.*;
public final class WorkflowTestContracts {
    private WorkflowTestContracts() {}
    public record WorkflowRequest(@NotBlank String month,@NotEmpty @Size(max=10) List<@NotBlank String> mandateIds,
        @NotBlank String sourceId,@NotBlank String recipientId,@NotBlank String firstDate,@NotBlank String endDate,
        @Min(1) @Max(28) int dayOfMonth,@AssertTrue boolean onlyConfirmedSavings) {
        public WorkflowRequest { mandateIds=mandateIds==null?List.of():List.copyOf(mandateIds); }
    }
    public record StepView(String key,String kind,String status,String actionId,long inputVersion,
                           long savingMinor,String errorCode) {}
    public record WorkflowView(UUID id,long version,String state,WorkflowRequest request,long estimatedSavingMinor,
        boolean partial,List<StepView> steps,Map<String,Object> pendingAction,Map<String,Object> analysis) {}
}
