package cn.safebank.vo;
/** businessDate 是本次请求的服务端业务日期（真实时钟，Asia/Shanghai）；前端据此展示当前日期，不使用浏览器本地日期。 */
public record SessionVO(String user,String csrf,String mode,String model,String authentication,boolean faultsEnabled,String businessDate) {
}
