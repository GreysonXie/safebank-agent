package cn.safebank.dao;
import cn.safebank.entity.ScheduleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface ScheduleMapper {
    int insert(ScheduleEntity row);
    int update(ScheduleEntity row);
    List<ScheduleEntity> selectByUser(@Param("userId") String userId);
    ScheduleEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
}
