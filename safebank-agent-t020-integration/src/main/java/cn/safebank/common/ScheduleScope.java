package cn.safebank.common;
import cn.safebank.common.RiskTypes.RiskLevel;
import java.time.LocalDate;
public record ScheduleScope(String sourceId,String recipientId,long amountMinor,String currency,LocalDate firstDate,LocalDate endDate,int dayOfMonth,String timezone,String recurrence,String policyVersion,RiskLevel maximumRiskLevel) {}
