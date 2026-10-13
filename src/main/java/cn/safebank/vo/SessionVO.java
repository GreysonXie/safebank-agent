package cn.safebank.vo;
public record SessionVO(String user,String csrf,String mode,String model,String authentication,boolean faultsEnabled) {
}
