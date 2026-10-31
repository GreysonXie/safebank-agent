package cn.safebank.dao;
import cn.safebank.entity.RiskDecisionEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface RiskDecisionMapper {
    RiskDecisionEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    RiskDecisionEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<RiskDecisionEntity> selectByUser(@Param("userId") String userId);
    List<RiskDecisionEntity> selectByAction(@Param("userId") String userId,@Param("actionId") String actionId);
    int insert(RiskDecisionEntity row);
}
