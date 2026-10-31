package cn.safebank.service;
import cn.safebank.BankException;
import org.springframework.stereotype.Service;
@Service public class TransferValidationService {
    private final AccountService accounts;
    private final RecipientService recipients;
    public TransferValidationService(AccountService accounts,RecipientService recipients){
        this.accounts=accounts;
        this.recipients=recipients;
    }
    public void validate(String userId,String sourceId,String recipientId,long amountMinor){
        if(amountMinor<=0)throw new BankException("VALIDATION_ERROR","金额必须为正整数分");
        long available=accounts.balance(userId,sourceId);
        var target=recipients.owned(userId,recipientId);
        if(sourceId.equals(target.getAccountId()))throw new BankException("VALIDATION_ERROR","不能转入同一账户");
        if(amountMinor>available)throw new BankException("INSUFFICIENT_FUNDS","可用余额不足");
        if(amountMinor>1000000)throw new BankException("LIMIT_EXCEEDED","演示单笔限额为 10,000 元");
    }
}
