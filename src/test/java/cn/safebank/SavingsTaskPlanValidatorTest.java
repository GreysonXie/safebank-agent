package cn.safebank;
import cn.safebank.service.SavingsTaskPlanValidator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SavingsTaskPlanValidatorTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode plan() throws Exception{return (ObjectNode)json.readTree(Files.readString(Path.of("agent-service/tests/fixtures/savings_task_plan.json")));}
 @Test void acceptsUnapprovedPlan() throws Exception {assertEquals(6,new SavingsTaskPlanValidator(json).validate(plan()).nodes().size());}
 @Test void rejectsMissingDependencyAndForgedMoney() throws Exception {
  var p=plan();((ObjectNode)p.path("nodes").get(3).path("params")).put("amountMinor",100);
  assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(p));
  var q=plan();((ArrayNode)q.path("nodes").get(4).path("dependsOn")).remove(1);
  assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(q));
 }
 @Test void rejectsCycleAndDuplicateServices() throws Exception {
  var p=plan();((ArrayNode)p.path("nodes").get(0).path("dependsOn")).add("t");
  assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(p));
  var q=plan();((ObjectNode)q.path("nodes").get(3).path("params")).put("merchantQuery","音乐服务");
  assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(q));
 }
 @Test void acceptsOneAndFourButRejectsFiveRevocations() throws Exception {
  for(int count:new int[]{1,4,5}){
   var p=plan();var old=(ArrayNode)p.get("nodes");var nodes=json.createArrayNode();nodes.add(old.get(0));nodes.add(old.get(1));
   var deps=json.createArrayNode();
   for(int i=0;i<count;i++){var r=(ObjectNode)old.get(2).deepCopy();r.put("id","r"+i);((ObjectNode)r.get("params")).put("merchantQuery","服务"+i);nodes.add(r);deps.add("r"+i);}
   var sum=(ObjectNode)old.get(4).deepCopy();sum.set("dependsOn",deps);nodes.add(sum);nodes.add(old.get(5));p.set("nodes",nodes);
   if(count<=4)assertEquals(count+4,new SavingsTaskPlanValidator(json).validate(p).nodes().size());
   else assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(p));
  }
 }
 @Test void rejectsUnknownFieldsAndBooleanVersion() throws Exception {
  var p=plan();p.put("userId","u2");assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(p));
  var q=plan();q.put("schemaVersion",true);assertThrows(BankException.class,()->new SavingsTaskPlanValidator(json).validate(q));
 }
}
