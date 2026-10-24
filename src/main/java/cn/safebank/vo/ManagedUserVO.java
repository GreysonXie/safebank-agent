package cn.safebank.vo;

public record ManagedUserVO(String id,String name,String phone,String gender,String note,
        String accountId,String accountLabel,long balanceMinor,boolean ownAccount,boolean savingsTarget) {}
