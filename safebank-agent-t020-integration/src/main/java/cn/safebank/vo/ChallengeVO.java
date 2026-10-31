package cn.safebank.vo;
import java.time.Instant;
public record ChallengeVO(String challengeId,String actionId,String decisionId,Instant expiresAt,String status,String verificationMode) {}
