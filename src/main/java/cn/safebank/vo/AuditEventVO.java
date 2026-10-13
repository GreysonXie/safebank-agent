package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record AuditEventVO(@JsonProperty("action_id") String actionId,String event,@JsonProperty("recorded_at") java.time.OffsetDateTime recordedAt) {
}
