package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record TransactionVO(String id, String merchant, String category, @JsonProperty("amount_minor") Long amountMinor, @JsonProperty("booked_on") java.time.LocalDate bookedOn) {
}
