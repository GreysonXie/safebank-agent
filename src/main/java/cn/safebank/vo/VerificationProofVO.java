package cn.safebank.vo;
import java.time.Instant;
public record VerificationProofVO(String verificationProofId,String actionId,String decisionId,Instant expiresAt,String verificationMode) {}
