package cn.safebank.dao;
import cn.safebank.entity.ScheduleOccurrenceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface ScheduleOccurrenceMapper {
    ScheduleOccurrenceEntity selectOwnedByAction(@Param("userId") String userId,@Param("actionId") String actionId);
    List<ScheduleOccurrenceEntity> selectByUser(@Param("userId") String userId);
    ScheduleOccurrenceEntity selectInMonth(@Param("scheduleId") String scheduleId,@Param("first") java.time.LocalDate first,@Param("last") java.time.LocalDate last);
    ScheduleOccurrenceEntity select(@Param("scheduleId") String scheduleId,@Param("dueDate") java.time.LocalDate dueDate);
    int insert(ScheduleOccurrenceEntity row);
}
