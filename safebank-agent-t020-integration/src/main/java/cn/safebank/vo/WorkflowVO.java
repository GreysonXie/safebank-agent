package cn.safebank.vo;
import cn.safebank.dto.WorkflowRequestDTO;
import java.util.*;
public record WorkflowVO(UUID id,long version,String state,WorkflowRequestDTO request,long estimatedSavingMinor, boolean partial,List<WorkflowStepVO> steps,PendingActionVO pendingAction,StatementVO analysis) {
}
