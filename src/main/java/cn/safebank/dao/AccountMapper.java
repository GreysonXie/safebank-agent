package cn.safebank.dao;
import cn.safebank.entity.AccountEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface AccountMapper {
    List<AccountEntity> selectByUser(@Param("userId") String userId);
    AccountEntity selectOwned(@Param("userId") String userId,@Param("accountId") String accountId);
    AccountEntity selectById(@Param("accountId") String accountId);
    AccountEntity lockById(@Param("accountId") String accountId);
    int debit(@Param("accountId") String accountId,@Param("amountMinor") long amountMinor);
    int credit(@Param("accountId") String accountId,@Param("amountMinor") long amountMinor);
}
