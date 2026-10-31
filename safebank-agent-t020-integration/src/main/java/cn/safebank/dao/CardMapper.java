package cn.safebank.dao;
import cn.safebank.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface CardMapper {
    List<BankCardEntity> list(@Param("userId") String userId);
    BankCardEntity owned(@Param("userId") String userId,@Param("id") String id);
    BankCardEntity lock(@Param("userId") String userId,@Param("id") String id);
    int insert(BankCardEntity card);
    int update(BankCardEntity card);
    int insertOperation(CardOperationEntity operation);
    CardOperationEntity operation(@Param("userId") String userId,@Param("id") String id);
    CardOperationEntity lockOperation(@Param("userId") String userId,@Param("id") String id);
    List<CardOperationEntity> history(@Param("userId") String userId);
    int finish(CardOperationEntity operation);
}
