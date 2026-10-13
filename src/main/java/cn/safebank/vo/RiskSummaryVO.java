package cn.safebank.vo;
import java.time.Instant;
public record RiskSummaryVO(java.time.LocalDate businessDate,long committedMinor,long reservedMinor,long unverifiedMinor,Instant lockedUntil,boolean reconciliationRequired) {}
