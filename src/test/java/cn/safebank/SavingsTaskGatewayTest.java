package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.entity.AgentToolCallReceiptEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SavingsTaskGatewayTest {
 @Test void zeroVerifiedCountIsNotExecutionSuccessButCannotHideSuccess() {
  var grounding=new GroundedAnswer(new ObjectMapper(),List.of(new BankReplyComposer.Evidence("agent_savings_task",Map.of("phase","任务已登记","progress",Map.of("verified_revocations",0)))));
  String count="已核验撤销{{savings_task.progress.verified_revocations}}笔，银行操作尚未执行。";
  assertDoesNotThrow(()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",count))));
  for(String bad:List.of(count+"撤销成功。","已核验撤销1笔。{{savings_task.phase}}","已核验撤销{{savings_task.progress.verified_revocations}}笔，转账已完成。"))
   assertThrows(BankException.class,()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",bad))));
 }
 @Test void preparedTaskCannotClaimBankScheduleRegistered() {
  var grounding=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task",Map.of("phase","任务已登记，银行操作仍须逐项确认"))));
  for(String bad:List.of("定时储蓄委托已登记。","已登记定时储蓄委托。","定时委托登记成功。"))
   assertThrows(BankException.class,()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",bad+"{{savings_task.phase}}"))));
  assertDoesNotThrow(()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer","任务计划已登记，定时储蓄委托尚未登记。{{savings_task.phase}}"))));
 }
 final ObjectMapper json=new ObjectMapper();
 AgentCapabilityService capabilities(){return new AgentCapabilityService(null,null,null,null,null,json,null,null);}
 @Test void scopesOnlyAddPreparationAndReadonlyStatus(){assertTrue(AgentCapabilityService.SCOPES.containsAll(Set.of("savings_task_prepare","savings_task_status")));assertFalse(AgentCapabilityService.SCOPES.contains("execute_savings_task"));}
 @Test void taskReceiptBecomesGroundedPreparationEvidence() throws Exception {
  var row=new AgentToolCallReceiptEntity();row.setToolName("savings_task_prepare");row.setResultJson("{\"state\":\"AWAITING_AUTH\",\"request\":{\"month\":\"2026-09\"}}");
  var evidence=capabilities().evidence(List.of(row));
  var grounding=new GroundedAnswer(json,evidence);assertTrue(grounding.factCatalog().containsKey("savings_task.phase"));
  assertThrows(BankException.class,()->grounding.render(json.readTree("{\"kind\":\"analysis\",\"answer\":\"已完成转账。\"}")));
 }
 @Test void failedPreparationCannotBecomePositiveEvidence(){
  var row=new AgentToolCallReceiptEntity();row.setToolName("savings_task_prepare");row.setResultJson("{\"code\":\"QUERY_CONFIRMATION_REQUIRED\"}");assertTrue(capabilities().evidence(List.of(row)).isEmpty());
 }
 @Test void registeredTaskCanSayCompletedRegistrationButNotCompletedBankOperations() {
  var grounding=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task",Map.of("phase","任务已登记，银行操作仍须逐项确认","state","AWAITING_AUTH"))));
  String valid="这个计划目前只是已完成登记，银行侧操作还没有执行。当前信息为{{savings_task.phase}}，请核对操作卡。";
  assertDoesNotThrow(()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",valid))));
  assertDoesNotThrow(()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer","计划目前只是已完成登记，银行侧还没有执行，也谈不上已经撤销代扣。当前信息{{savings_task.phase}}。"))));
  for(String invalid:List.of("计划已完成登记，已完成转账。{{savings_task.phase}}","计划的转账已完成登记。{{savings_task.phase}}","已完成撤销登记。{{savings_task.phase}}","计划已完成登记，操作已完成。{{savings_task.phase}}","谈不上已经撤销，撤销成功。{{savings_task.phase}}"))
   assertThrows(BankException.class,()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",invalid))));
  var noTask=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_accounts",List.of(Map.of("id","a","balance_minor",1)))));
  assertThrows(BankException.class,()->noTask.render(json.valueToTree(Map.of("kind","general","answer","计划已完成登记。"))));
 }
 @Test void taskAnalysisMonthCannotBecomeSavingsPeriodOrFirstTransferDate(){
  var grounding=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task",Map.of("phase","任务已登记","analysis_month","2026-09"))));
  assertDoesNotThrow(()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer","账单分析月为{{savings_task.analysis_month}}，任务信息为{{savings_task.phase}}。"))));
  for(String label:List.of("储蓄周期为","首次日期为","首次转存时间是","结束日期为"))assertThrows(BankException.class,()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",label+"{{savings_task.analysis_month}}。任务信息{{savings_task.phase}}。"))));
 }
 @Test void futureArrivalDenialIsNotExecutionClaimAndDoesNotMaskFollowingSuccess(){
  var grounding=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task_status",Map.of("phase","原任务状态快照","state","VERIFIED_SUCCESS"))));
  for(String denial:List.of("并不代表之后每个月的钱已经到账","这不等于之后每个月的钱已经到账","不代表未来转账已经到账")){
   String valid="任务状态为{{savings_task_status.state}}，"+denial+"。";
   assertDoesNotThrow(()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",valid))));
   assertThrows(BankException.class,()->grounding.render(json.valueToTree(Map.of("kind","analysis","answer",valid+"转账已经完成。"))));
  }
 }
}
