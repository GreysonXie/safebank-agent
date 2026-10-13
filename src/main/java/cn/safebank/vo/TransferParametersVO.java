package cn.safebank.vo;
import com.fasterxml.jackson.annotation.*;
@JsonInclude(JsonInclude.Include.NON_NULL) public record TransferParametersVO(@JsonProperty("source_id") String sourceId,@JsonProperty("recipient_id") String recipientId, @JsonProperty("recipient_label") String recipientLabel,@JsonProperty("amount_minor") long amountMinor,String currency, @JsonProperty("fee_minor") Long feeMinor,@JsonProperty("schedule_id") String scheduleId,@JsonProperty("occurrence_date") String occurrenceDate) implements ActionParametersVO {
}
