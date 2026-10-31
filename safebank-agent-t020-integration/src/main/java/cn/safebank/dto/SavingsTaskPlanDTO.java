package cn.safebank.dto;
import java.util.List;
public record SavingsTaskPlanDTO(int schemaVersion,String goal,String savingsPolicy,String analysisMonth,List<TaskNodeProposalDTO> nodes) {
 public SavingsTaskPlanDTO {nodes=List.copyOf(nodes);}
}
