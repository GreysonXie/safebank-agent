package cn.safebank.entity;
import java.time.OffsetDateTime;
public record BankCardEntity(String id,String userId,String accountId,String label,String cardType,
        String suffix,String status,long perTransactionLimitMinor,long dailyLimitMinor,
        boolean onlineEnabled,boolean overseasEnabled,long version,OffsetDateTime createdAt) {}
