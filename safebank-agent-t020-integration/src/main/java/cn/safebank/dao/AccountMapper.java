package cn.safebank.dao;
import cn.safebank.entity.AccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface AccountMapper {
    int insert(AccountEntity account);
    List<AccountEntity> selectByUser(@Param("userId") String userId);
    AccountEntity selectOwned(@Param("userId") String userId,@Param("accountId") String accountId);
    AccountEntity selectById(@Param("accountId") String accountId);
    AccountEntity lockById(@Param("accountId") String accountId);
    AccountEntity lockFirstOwned(@Param("userId") String userId);
    int debit(@Param("accountId") String accountId,@Param("amountMinor") long amountMinor);
    int credit(@Param("accountId") String accountId,@Param("amountMinor") long amountMinor);
}
