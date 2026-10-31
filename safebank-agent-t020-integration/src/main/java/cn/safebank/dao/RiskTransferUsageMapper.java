package cn.safebank.dao;
import cn.safebank.entity.RiskTransferUsageEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface RiskTransferUsageMapper {
    RiskTransferUsageEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    RiskTransferUsageEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<RiskTransferUsageEntity> selectByUser(@Param("userId") String userId);
    int insert(RiskTransferUsageEntity row);
    int update(RiskTransferUsageEntity row);
}
