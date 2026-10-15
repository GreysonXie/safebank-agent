package cn.safebank.vo;
import java.util.*;
public record TaskPlanVO(UUID workflowId,long version,String state,String savingsPolicy,String analysisMonth,List<TaskPlanNodeVO> nodes){public TaskPlanVO{nodes=List.copyOf(nodes);}}
