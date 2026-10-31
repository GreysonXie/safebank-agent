package cn.safebank.dao.projection;

public record ManagedUserRow(String id,String name,String phone,String gender,String note,
        String accountId,String accountLabel,long balanceMinor,String accountUserId,boolean savingsTarget) {}
