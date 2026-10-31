package cn.safebank.dao;
import cn.safebank.entity.WorkflowEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface WorkflowMapper {
    WorkflowEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    WorkflowEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<WorkflowEntity> selectByUser(@Param("userId") String userId);
    int insert(WorkflowEntity row);
    int updateState(@Param("userId") String userId,@Param("id") String id,@Param("state") String state,@Param("updatedAt") java.time.OffsetDateTime updatedAt);
    int revise(@Param("userId") String userId,@Param("id") String id,@Param("version") long version,@Param("request") String request,@Param("analysis") String analysis);
}
