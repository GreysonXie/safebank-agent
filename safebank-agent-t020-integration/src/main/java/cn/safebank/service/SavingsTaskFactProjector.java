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
 private static boolean hasRegisteredMandates(JsonNode request){
  var ids=request.path("mandateIds");if(!ids.isArray()||ids.isEmpty()||ids.size()>10)return false;
  var unique=new HashSet<String>();for(var id:ids)if(!id.isTextual()||id.asText().isBlank()||!unique.add(id.asText()))return false;
  return true;
 }
 private static boolean hasScheduledTransfer(JsonNode request){
  for(String field:List.of("sourceId","recipientId","firstDate","endDate"))if(!request.path(field).isTextual()||request.path(field).asText().isBlank())return false;
  var day=request.path("dayOfMonth");return day.isIntegralNumber()&&day.canConvertToInt()&&day.intValue()>=1&&day.intValue()<=31;
 }
 public static Map<String,Object> project(JsonNode receipt,boolean preparation){
  if(receipt==null||!receipt.isObject()||receipt.has("code"))return Map.of();
  var result=new LinkedHashMap<String,Object>();String state=receipt.path("state").asText();
  result.put("phase",preparation?"任务已登记，银行操作仍须在任务卡逐项确认":"原任务的银行状态快照");text(result,"state",receipt.path("state"));result.put("state_label",stateLabel(state));
  var request=receipt.path("request");text(result,"analysis_month",request.path("month"));
  var scope=new LinkedHashMap<String,Object>();
  if(hasRegisteredMandates(request))scope.put("revocations_count",request.path("mandateIds").size());
  if(hasScheduledTransfer(request))scope.put("scheduled_transfers_count",1);
  if(!scope.isEmpty()){
   scope.put("scope_label","登记计划范围，不代表银行操作已执行");
   scope.put("steps_scope","步骤列表仅包含按步骤标识合并后的当前动作记录；后续步骤尚未出现在列表中，不等于未纳入登记计划");
   scope.put("names_scope","本投影不提供完整服务名称目录，不能据当前步骤缺少某服务就认定其未登记");
   result.put("plan_scope",scope);
  }
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
  var progress=new LinkedHashMap<String,Object>();
  if(hasRegisteredMandates(request))progress.put("requested_revocations",request.path("mandateIds").size());
  if(receipt.path("steps").isArray()){progress.put("created_steps_count",latest.size());progress.put("verified_revocations",verified);}
  if(!progress.isEmpty())result.put("progress",progress);
  return Collections.unmodifiableMap(result);
 }
}
