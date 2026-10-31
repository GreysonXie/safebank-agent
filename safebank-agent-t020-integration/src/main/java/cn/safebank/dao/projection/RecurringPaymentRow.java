package cn.safebank.dao.projection;
public record RecurringPaymentRow(String merchant,long occurrences,java.time.LocalDate firstSeen,java.time.LocalDate lastSeen,long minAmountMinor,long maxAmountMinor) {}
