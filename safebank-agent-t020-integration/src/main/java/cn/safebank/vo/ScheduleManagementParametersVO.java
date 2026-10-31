package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record ScheduleManagementParametersVO(@JsonProperty("schedule_id") String scheduleId,String operation,
        @JsonProperty("expected_state_hash") String expectedStateHash,
        @JsonProperty("source_id") String sourceId,@JsonProperty("recipient_id") String recipientId,
        @JsonProperty("amount_minor") long amountMinor,@JsonProperty("first_date") String firstDate,
        @JsonProperty("end_date") String endDate,@JsonProperty("day_of_month") int dayOfMonth,
        String timezone) implements ActionParametersVO {}
