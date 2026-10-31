package cn.safebank.dao;
import cn.safebank.entity.RiskUserGuardEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface RiskUserGuardMapper {
    RiskUserGuardEntity selectForUpdate(@Param("userId") String userId);
    RiskUserGuardEntity select(@Param("userId") String userId);
    int insert(RiskUserGuardEntity row);
    int update(RiskUserGuardEntity row);
}
