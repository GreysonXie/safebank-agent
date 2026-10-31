package cn.safebank.service;
import cn.safebank.common.RiskTypes.RiskChannel;
import java.time.*;
public record RiskExecutionContext(String userId,String requestId,RiskChannel channel,Instant now,LocalDate businessDate,boolean demoMode) {}
