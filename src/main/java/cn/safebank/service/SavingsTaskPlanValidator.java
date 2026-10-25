package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.TaskNodeType;
import cn.safebank.dto.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent Java validation of untrusted plans, before any bank mutation. */
@Component public class SavingsTaskPlanValidator {
 private final ObjectMapper json;
 private static final Map<String,Set<String>> PARAMETERS=Map.of(
  "mandate_lookup",Set.of(),"account_query",Set.of(),"mandate_revoke",Set.of("merchantQuery"),
  "compute_savings",Set.of(),"schedule_create",Set.of("sourceQuery","recipientQuery","firstDate","endDate","dayOfMonth"));
 public SavingsTaskPlanValidator(ObjectMapper json){this.json=json;}
 private static void require(boolean ok){if(!ok)throw new BankException("INVALID_TASK_PLAN","任务计划格式或依赖无效");}
 private static Set<String> fields(JsonNode n){require(n!=null&&n.isObject());var out=new HashSet<String>();n.fieldNames().forEachRemaining(out::add);return out;}
 private static String text(JsonNode n,int maximum){require(n!=null&&n.isTextual()&&!n.asText().isBlank()&&n.asText().length()<=maximum);return n.asText();}
 public SavingsTaskPlanDTO validate(JsonNode proposal){
  var keys=fields(proposal);var required=Set.of("schemaVersion","goal","savingsPolicy","nodes");
  require(keys.containsAll(required)&&Set.of("schemaVersion","goal","savingsPolicy","nodes","analysisMonth").containsAll(keys));
  require(proposal.toString().getBytes(StandardCharsets.UTF_8).length<=16384);
  require(proposal.path("schemaVersion").isIntegralNumber()&&proposal.path("schemaVersion").canConvertToInt()&&proposal.path("schemaVersion").intValue()==1);
  String goal=text(proposal.get("goal"),200);require("verified_success_subset".equals(proposal.path("savingsPolicy").asText()));
  String month=null;
  if(proposal.hasNonNull("analysisMonth")){month=text(proposal.get("analysisMonth"),7);require(month.matches("20[0-9]{2}-(0[1-9]|1[0-2])"));}
  var input=proposal.path("nodes");require(input.isArray()&&input.size()>=5&&input.size()<=8);
  var nodes=new ArrayList<TaskNodeProposalDTO>();var by=new LinkedHashMap<String,TaskNodeProposalDTO>();
  var groups=new HashMap<String,List<TaskNodeProposalDTO>>();PARAMETERS.keySet().forEach(k->groups.put(k,new ArrayList<>()));
  for(var node:input){
   require(fields(node).equals(Set.of("id","type","params","dependsOn")));
   String id=text(node.get("id"),32);require(id.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")&&!by.containsKey(id));
   String kind=text(node.get("type"),32);require(PARAMETERS.containsKey(kind));
   var params=node.get("params");require(fields(params).equals(PARAMETERS.get(kind)));
   for(String field:PARAMETERS.get(kind)){
    var arg=params.get(field);
    if(field.equals("dayOfMonth"))require(arg.isIntegralNumber()&&arg.canConvertToInt()&&arg.intValue()>=1&&arg.intValue()<=31);
    else {String value=text(arg,100);if(field.endsWith("Date"))require(value.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}"));}
   }
   var dependencies=node.get("dependsOn");require(dependencies!=null&&dependencies.isArray());var deps=new ArrayList<String>();
   for(var dep:dependencies)deps.add(text(dep,32));require(new HashSet<>(deps).size()==deps.size());
   var dto=new TaskNodeProposalDTO(id,TaskNodeType.valueOf(kind),params,deps);nodes.add(dto);by.put(id,dto);groups.get(kind).add(dto);
  }
  for(String k:PARAMETERS.keySet())require(k.equals("mandate_revoke")?groups.get(k).size()>=1&&groups.get(k).size()<=4:groups.get(k).size()==1);
  var revocations=groups.get("mandate_revoke");var services=new HashSet<String>();
  for(var n:revocations)require(services.add(n.params().get("merchantQuery").asText().strip().toLowerCase(Locale.ROOT)));
  var seen=new HashSet<String>();
  while(seen.size()<nodes.size()){
   var ready=new HashSet<String>();for(var n:nodes)if(!seen.contains(n.id())&&seen.containsAll(n.dependsOn()))ready.add(n.id());
   require(!ready.isEmpty());seen.addAll(ready);
  }
  var m=groups.get("mandate_lookup").getFirst();var a=groups.get("account_query").getFirst();
  var s=groups.get("compute_savings").getFirst();var t=groups.get("schedule_create").getFirst();
  require(m.dependsOn().isEmpty()&&a.dependsOn().isEmpty());
  for(var r:revocations)require(r.dependsOn().equals(List.of(m.id())));
  require(new HashSet<>(s.dependsOn()).equals(new HashSet<>(revocations.stream().map(TaskNodeProposalDTO::id).toList())));
  require(new HashSet<>(t.dependsOn()).equals(Set.of(s.id(),a.id())));
  return new SavingsTaskPlanDTO(1,goal,"verified_success_subset",month,nodes);
 }
}
