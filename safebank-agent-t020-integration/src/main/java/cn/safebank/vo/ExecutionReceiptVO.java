package cn.safebank.vo;
import com.fasterxml.jackson.annotation.*;
public record ExecutionReceiptVO(@JsonUnwrapped ActionVO action, @JsonProperty("observed_at") @JsonInclude(JsonInclude.Include.NON_NULL) String observedAt, @JsonInclude(JsonInclude.Include.NON_NULL) String observation) implements ActionExecutionResultVO {
}
