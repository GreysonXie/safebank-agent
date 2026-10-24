package cn.safebank.vo;
public record BankCardVO(String id,String accountId,String accountLabel,long balanceMinor,String label,
        String cardType,String maskedNumber,String status,long perTransactionLimitMinor,
        long dailyLimitMinor,boolean onlineEnabled,boolean overseasEnabled,long version) {}
