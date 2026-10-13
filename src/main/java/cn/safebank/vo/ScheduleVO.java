package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record ScheduleVO(String id,@JsonProperty("user_id") String userId,@JsonProperty("source_id") String sourceId, @JsonProperty("recipient_id") String recipientId,@JsonProperty("amount_minor") Long amountMinor,@JsonProperty("day_of_month") Integer dayOfMonth, @JsonProperty("first_date") java.time.LocalDate firstDate,@JsonProperty("end_date") java.time.LocalDate endDate,String status) {
}
