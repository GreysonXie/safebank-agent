package cn.safebank.dao;
import cn.safebank.entity.RiskEventEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface RiskEventMapper {
    RiskEventEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    RiskEventEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<RiskEventEntity> selectByUser(@Param("userId") String userId);
    int insert(RiskEventEntity row);
}
