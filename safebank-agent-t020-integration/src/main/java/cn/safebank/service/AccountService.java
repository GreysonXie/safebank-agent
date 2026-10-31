package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.AccountMapper;
import cn.safebank.vo.AccountVO;
import org.springframework.stereotype.Service;
import java.util.List;
@Service public class AccountService {
    private final AccountMapper accounts;
    public AccountService(AccountMapper accounts){
        this.accounts=accounts;
    }
    public List<AccountVO> list(String userId){
        return accounts.selectByUser(userId).stream() .map(row->new AccountVO(row.getId(),row.getLabel(),row.getBalanceMinor())).toList();
    }
    public long balance(String userId,String accountId){
        var row=accounts.selectOwned(userId,accountId);
        if(row==null)throw new BankException("NOT_FOUND","资源不存在或不属于当前用户");
        return row.getBalanceMinor();
    }
}
