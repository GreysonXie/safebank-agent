package cn.safebank.vo;
import cn.safebank.common.*;
import com.fasterxml.jackson.annotation.*;
import java.time.OffsetDateTime;
public record ActionVO(String id,@JsonProperty("user_id") String userId,ActionKind kind,@JsonProperty("payload_hash") String payloadHash, ActionStatus status,@JsonProperty("expires_at") OffsetDateTime expiresAt,@JsonProperty("receipt_id") String receiptId, @JsonProperty("error_code") String errorCode,@JsonUnwrapped ActionParametersVO parameters,RiskDecisionVO security) implements PendingActionVO {
    public ActionVO(String id,String userId,ActionKind kind,String payloadHash,ActionStatus status,OffsetDateTime expiresAt,String receiptId,String errorCode,ActionParametersVO parameters){this(id,userId,kind,payloadHash,status,expiresAt,receiptId,errorCode,parameters,null);}
    public ActionVO withObservation(ActionStatus state,String receipt,String error){
        return new ActionVO(id,userId,kind,payloadHash,state,expiresAt,receipt,error,parameters,security);
    }
}
