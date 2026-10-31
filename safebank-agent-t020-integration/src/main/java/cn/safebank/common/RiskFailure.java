package cn.safebank.common;
import cn.safebank.vo.RiskDecisionVO;
import java.time.Instant;
public record RiskFailure(String code,String message,String actionId,RiskDecisionVO security,Instant retryAt) {}
