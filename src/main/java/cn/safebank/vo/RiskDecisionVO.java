package cn.safebank.vo;
import cn.safebank.common.RiskTypes.*;
import java.time.*;
public record RiskDecisionVO(String decisionId,String policyVersion,RiskLevel riskLevel,AssuranceRequirement requiredAssurance,String reasonCode,LocalDate businessDate,long effectiveMinor,long projectedMinor,Instant validUntil,String nextStep,String verificationMode,boolean needsRefresh) {}
