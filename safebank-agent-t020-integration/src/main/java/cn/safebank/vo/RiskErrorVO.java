package cn.safebank.vo;
import java.time.Instant;
public record RiskErrorVO(String code,String message,String actionId,RiskDecisionVO security,Instant retryAt) {}
