package cn.safebank.vo;
import com.fasterxml.jackson.annotation.*;
public record ScheduleParametersVO(@JsonProperty("source_id") String sourceId,@JsonProperty("recipient_id") String recipientId,@JsonProperty("amount_minor") long amountMinor, @JsonProperty("first_date") String firstDate,@JsonProperty("end_date") String endDate,@JsonProperty("day_of_month") int dayOfMonth,String timezone, @JsonProperty("insufficient_funds") String insufficientFunds) implements ActionParametersVO {
}
