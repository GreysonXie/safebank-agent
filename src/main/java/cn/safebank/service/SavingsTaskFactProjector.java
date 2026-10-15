package cn.safebank.service;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Pure projection of one owned, hash-checked workflow receipt; no new reads. */
public final class SavingsTaskFactProjector {
 private SavingsTaskFactProjector(){}
 public static String stateLabel(String state){return switch(state){
  case "AWAITING_AUTH","PLANNING"->"待逐项确认，尚未执行";case "AUTHORIZED"->"已授权，尚未提交";
  case "UNKNOWN"->"结果未知，需查询原动作";case "PENDING"->"银行处理中，需查询原动作";
  case "VERIFIED_SUCCESS"->"本项已核验完成";case "VERIFIED_FAILED"->"本项已核验失败";
  case "PARTIAL_SUCCESS"->"部分步骤已核验完成";case "CANCELLED"->"已取消";case "CANCEL_REQUESTED"->"后续已停止，原动作待核实";
  default->"状态未提供";};}
 private static String kindLabel(String kind){return switch(kind){case "REVOKE"->"撤销银行侧代扣";case "SCHEDULE"->"登记定时储蓄委托";default->"银行操作";};}
 private static void text(Map<String,Object> target,String key,JsonNode value){if(value.isTextual()&&!value.asText().isBlank())target.put(key,value.asText());}
 private static void number(Map<String,Object> target,String key,JsonNode value){if(value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=0)target.put(key,value.longValue());}
 public static Map<String,Object> project(JsonNode receipt,boolean preparation){
  if(receipt==null||!receipt.isObject()||receipt.has("code"))return Map.of();
  var result=new LinkedHashMap<String,Object>();String state=receipt.path("state").asText();
  result.put("phase",preparation?"任务已登记，银行操作仍须在任务卡逐项确认":"原任务的银行状态快照");text(result,"state",receipt.path("state"));result.put("state_label",stateLabel(state));
  var request=receipt.path("request");text(result,"analysis_month",request.path("month"));
  var schedule=new LinkedHashMap<String,Object>();text(schedule,"first_date",request.path("firstDate"));text(schedule,"end_date",request.path("endDate"));number(schedule,"day_of_month",request.path("dayOfMonth"));if(!schedule.isEmpty())result.put("schedule",schedule);
  number(result,"verified_monthly_saving_minor",receipt.path("estimatedSavingMinor"));
  result.put("saving_scope","仅本任务已核验撤销的月度约定扣费，不是账户新增余额，也不保证商户未来收费或退款");
  var pending=receipt.path("pendingAction");boolean uncertain=Set.of("UNKNOWN","PENDING","CANCEL_REQUESTED").contains(state)||Set.of("UNKNOWN","PENDING").contains(pending.path("status").asText());
  result.put("continuation",uncertain?"先查询原动作，禁止继续、改参或重复提交":"按当前确认项逐项核对；定时委托登记不代表未来到账");
  if(pending.isObject()&&!pending.path("id").asText().isBlank()){
   var current=new LinkedHashMap<String,Object>();current.put("kind_label",kindLabel(pending.path("kind").asText()));current.put("state_label",stateLabel(pending.path("status").asText()));
   text(current,"merchant",pending.path("merchant"));text(current,"billing_interval",pending.path("billing_interval"));
   number(current,"charge_amount_minor",pending.path("charge_amount_minor"));number(current,"amount_minor",pending.path("amount_minor"));
   if(current.containsKey("charge_amount_minor"))current.put("charge_scope","服务原约定的每期扣费；未提供撤销手续费，不能据此认定撤销收费或免收费");
   result.put("pending",current);
  }
  var latest=new LinkedHashMap<String,JsonNode>();
  for(var step:receipt.path("steps"))if(step.isObject()&&!step.path("key").asText().isBlank()){
   String key=step.path("key").asText();var previous=latest.get(key);if(previous==null||step.path("inputVersion").asLong()>previous.path("inputVersion").asLong())latest.put(key,step);
  }
  var steps=new ArrayList<Map<String,Object>>();int verified=0;
  for(var step:latest.values()){
   String stepState=step.path("status").asText();
   if(!pending.path("id").asText().isBlank()&&pending.path("id").asText().equals(step.path("actionId").asText()))stepState=pending.path("status").asText();
   var row=new LinkedHashMap<String,Object>();row.put("kind_label",kindLabel(step.path("kind").asText()));row.put("state_label",stateLabel(stepState));
   if(stepState.equals("VERIFIED_SUCCESS")){number(row,"saving_minor",step.path("savingMinor"));if(step.path("kind").asText().equals("REVOKE"))verified++;}
   steps.add(row);
  }
  if(!steps.isEmpty())result.put("steps",steps);
  if(request.path("mandateIds").isArray())result.put("progress",Map.of("requested_revocations",request.path("mandateIds").size(),"verified_revocations",verified));
  return Collections.unmodifiableMap(result);
 }
}
