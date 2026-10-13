package cn.safebank.dao;
import cn.safebank.entity.WorkflowStepEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface WorkflowStepMapper {
    List<WorkflowStepEntity> selectByWorkflow(@Param("workflowId") String workflowId);
    int insert(WorkflowStepEntity row);
    int updateObserved(@Param("workflowId") String workflowId,@Param("actionId") String actionId,@Param("status") String status,@Param("errorCode") String errorCode,@Param("evidence") String evidence);
    int cancel(@Param("workflowId") String workflowId,@Param("actionId") String actionId);
}
