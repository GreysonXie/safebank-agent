package cn.safebank.dao;
import cn.safebank.entity.ScheduleOccurrenceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface ScheduleOccurrenceMapper {
    ScheduleOccurrenceEntity selectOwnedByAction(@Param("userId") String userId,@Param("actionId") String actionId);
    ScheduleOccurrenceEntity select(@Param("scheduleId") String scheduleId,@Param("dueDate") java.time.LocalDate dueDate);
    int insert(ScheduleOccurrenceEntity row);
}
