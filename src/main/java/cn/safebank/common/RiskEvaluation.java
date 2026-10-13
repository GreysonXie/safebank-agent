package cn.safebank.common;
import cn.safebank.common.RiskTypes.*;
import java.time.LocalDate;
public record RiskEvaluation(RiskLevel level,AssuranceRequirement requiredAssurance,long amountMinor,long effectiveMinor,long projectedMinor,String reasonCode,LocalDate businessDate,String policyVersion) {}
