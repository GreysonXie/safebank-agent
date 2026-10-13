package cn.safebank.dao;
import cn.safebank.entity.ExecutionObservationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface ExecutionObservationMapper {
    ExecutionObservationEntity selectOwned(@Param("userId") String userId,@Param("actionId") String actionId);
    default int upsert(ExecutionObservationEntity row) {int count=write(row);return count==2?1:count;}
    int write(ExecutionObservationEntity row);
    int deleteOwned(@Param("userId") String userId,@Param("actionId") String actionId);
}
