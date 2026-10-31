package cn.safebank.verification;
import java.time.Instant;
import cn.safebank.common.RiskTypes.VerificationMethod;
public record VerificationDelivery(String challengeId,String userId,VerificationMethod method,String oneTimeCode,Instant expiresAt) {
    @Override public String toString(){return "VerificationDelivery[REDACTED]";}
}
