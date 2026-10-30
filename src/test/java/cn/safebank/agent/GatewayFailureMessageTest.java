package cn.safebank.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayFailureMessageTest {
 @Test void knownRejectionExplainsConfigurationWithoutSuggestingRetry() {
  for(int status:new int[]{400,401,403,404,405,413,415,422}) {
   var message=GatewayCoordinator.reviewMessage("MODEL_REQUEST_REJECTED_"+status);
   assertTrue(message.contains("拒绝"));assertTrue(message.contains("原轮次"));assertFalse(message.contains("重新提交"));
  }
 }
 @Test void queryFailuresExplainWhatToDoNextWithoutClaimingOperations() {
  for(String code:new String[]{"EFFECT_FAILED","BANK_CALLBACK_REJECTED"}) {
   var message=GatewayCoordinator.failureMessage(code);
   assertTrue(message.contains("没有成功")||message.contains("没有返回确定结果"),code+" 应说明结果状态");
   assertTrue(message.contains("未执行任何操作"),code+" 不得声称执行了操作");
  }
  var history=GatewayCoordinator.failureMessage("HISTORY_QUERY_REQUIRED");
  assertTrue(history.contains("产品"),"历史查询失败要引导客户说产品");
  assertTrue(history.contains("月份")||history.contains("没有执行任何操作"));
  var unknown=GatewayCoordinator.failureMessage("MODEL_EFFECT_UNKNOWN");
  assertTrue(unknown.contains("未取得确定结果")||unknown.contains("核对原请求"),"按真实状态提示核对原请求");
  assertFalse(unknown.contains("重启"),"未知结果文案不能提重启");
  assertFalse(unknown.contains("未执行任何操作"),"未知结果不能断言没有执行过操作");
 }
 @Test void uncertainAndUnrecognizedErrorsDoNotMasqueradeAsRejection() {
  assertTrue(GatewayCoordinator.reviewMessage("MODEL_EFFECT_UNKNOWN").contains("尚未确定"));
  for(String code:new String[]{null,"MODEL_REQUEST_REJECTED_500","MODEL_REQUEST_REJECTED_429","private provider text"}) {
   String message=GatewayCoordinator.reviewMessage(code);
   assertFalse(message.contains("private"));assertFalse(message.contains("模型服务明确拒绝"));
  }
 }
}
