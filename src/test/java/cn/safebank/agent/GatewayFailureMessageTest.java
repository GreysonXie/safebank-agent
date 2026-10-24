package cn.safebank.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayFailureMessageTest {
 @Test void quotaErrorsExplainWhichLimitWasReached(){
  assertTrue(GatewayCoordinator.reviewMessage("MODEL_REQUEST_BUDGET_EXCEEDED").contains("累计"));
  assertTrue(GatewayCoordinator.reviewMessage("MODEL_TURN_BUDGET_EXCEEDED").contains("本轮"));
  assertTrue(GatewayCoordinator.reviewMessage("MODEL_DEBUG_BUDGET_INVALID").contains("用量记录"));
 }

 @Test void knownRejectionExplainsConfigurationWithoutSuggestingRetry() {
  for(int status:new int[]{400,401,403,404,405,413,415,422}) {
   var message=GatewayCoordinator.reviewMessage("MODEL_REQUEST_REJECTED_"+status);
   assertTrue(message.contains("拒绝"));assertTrue(message.contains("原轮次"));assertFalse(message.contains("重新提交"));
  }
 }
 @Test void uncertainAndUnrecognizedErrorsDoNotMasqueradeAsRejection() {
  assertTrue(GatewayCoordinator.reviewMessage("MODEL_EFFECT_UNKNOWN").contains("尚未确定"));
  for(String code:new String[]{null,"MODEL_REQUEST_REJECTED_500","MODEL_REQUEST_REJECTED_429","private provider text"}) {
   String message=GatewayCoordinator.reviewMessage(code);
   assertFalse(message.contains("private"));assertFalse(message.contains("模型服务明确拒绝"));
  }
 }
}
