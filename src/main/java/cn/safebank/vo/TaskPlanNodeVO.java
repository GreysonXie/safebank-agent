package cn.safebank.vo;
import java.util.List;
public record TaskPlanNodeVO(String id,String type,List<String> dependsOn,String displayState,String actionId,String errorCode){public TaskPlanNodeVO{dependsOn=List.copyOf(dependsOn);}}
