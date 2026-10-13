package cn.safebank.vo;
public record WorkflowStepVO(String key,String kind,String status,String actionId,long inputVersion,long savingMinor,String errorCode) {
}
