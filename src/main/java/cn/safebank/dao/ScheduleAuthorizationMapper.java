package cn.safebank.dao;
import cn.safebank.entity.ScheduleAuthorizationEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface ScheduleAuthorizationMapper {
    ScheduleAuthorizationEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    ScheduleAuthorizationEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<ScheduleAuthorizationEntity> selectByUser(@Param("userId") String userId);
    int insert(ScheduleAuthorizationEntity row);
    int update(ScheduleAuthorizationEntity row);
}
