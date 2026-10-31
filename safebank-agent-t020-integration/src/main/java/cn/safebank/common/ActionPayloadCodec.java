package cn.safebank.common;
import cn.safebank.BankException;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
@Component public class ActionPayloadCodec {
    private final ObjectMapper json;
    public ActionPayloadCodec(ObjectMapper json){
        this.json=json;
    }
    public String encode(ActionParametersVO parameters){
        try{
            return json.writeValueAsString(parameters);
        }
        catch(Exception e){
            throw new BankException("VALIDATION_ERROR","参数无法序列化");
        }
    }
    public ActionParametersVO decode(ActionKind kind,String payload){
        try{
            return switch(kind){
                case TRANSFER->json.readValue(payload,TransferParametersVO.class);
                case REVOKE->json.readValue(payload,RevokeParametersVO.class);
                case QUERY_MANDATES->json.readValue(payload,QueryParametersVO.class);
                case SCHEDULE->json.readValue(payload,ScheduleParametersVO.class);
                case ACTIVATE_MANDATE->json.readValue(payload,MandateActivationParametersVO.class);
                case MANAGE_SCHEDULE->json.readValue(payload,ScheduleManagementParametersVO.class);
            }
            ;
        }
        catch(Exception e){
            throw new BankException("CONFLICT","操作记录损坏");
        }
    }
    public String hash(String userId,ActionKind kind,String payload){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((userId+":"+kind+":"+payload).getBytes(StandardCharsets.UTF_8)));
        }
        catch(Exception e){
            throw new IllegalStateException(e);
        }
    }
    public ActionKind kind(String value){
        try{
            return ActionKind.valueOf(value);
        }
        catch(Exception e){
            throw new BankException("CONFLICT","不支持的操作类型");
        }
    }
}
