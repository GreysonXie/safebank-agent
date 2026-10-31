package cn.safebank.dao;
import cn.safebank.entity.BankFaultEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface BankFaultMapper {
    BankFaultEntity selectOwned(@Param("userId") String userId,@Param("actionId") String actionId);
    /** Preserve the one-row DAO contract across MySQL insert/update counts. */
    default int upsert(BankFaultEntity row) {int count=write(row);return count==2?1:count;}
    int write(BankFaultEntity row);
}
