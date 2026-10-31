package cn.safebank.dto;
import cn.safebank.common.TaskNodeType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
public record TaskNodeProposalDTO(String id,TaskNodeType type,JsonNode params,List<String> dependsOn) {
 public TaskNodeProposalDTO {params=params.deepCopy();dependsOn=List.copyOf(dependsOn);}
}
