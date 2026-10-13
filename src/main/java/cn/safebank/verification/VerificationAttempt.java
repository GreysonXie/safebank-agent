package cn.safebank.verification;
import cn.safebank.common.RiskTypes.VerificationMethod;
public record VerificationAttempt(String challengeId,String userId,VerificationMethod method,String providedCode,String providerReference) {
    @Override public String toString(){return "VerificationAttempt[REDACTED]";}
}
