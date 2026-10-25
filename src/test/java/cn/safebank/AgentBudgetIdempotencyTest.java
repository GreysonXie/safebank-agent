package cn.safebank;
import cn.safebank.model.DebugRequestBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class AgentBudgetIdempotencyTest {
 @TempDir Path temp;
 String key(int i){return "%064x".formatted(i);}
 @Test void originalElevenReservationsRemainAndSameEffectReuses() {
  var path=temp.resolve("budget.json");var b=new DebugRequestBudget(path,20);
  for(int i=0;i<11;i++)b.reserve();
  String id=b.reserveEffect(key(1),key(2),"turn-one","plan");
  assertEquals(id,new DebugRequestBudget(path,20).reserveEffect(key(1),key(2),"turn-one","plan"));
  assertEquals(12,b.snapshot().reserved());
  assertThrows(BankException.class,()->b.reserveEffect(key(1),key(3),"turn-one","plan"));
  assertThrows(BankException.class,()->b.reserveEffect(key(1),key(2),"turn-other","plan"));
 }
 @Test void sixthEffectInSameTurnIsRejectedButCumulativeUsageIsUnlimited() {
  var b=new DebugRequestBudget(temp.resolve("budget.json"),20);
  String first=b.reserveEffect(key(1),key(9),"turn","plan");
  for(int i=2;i<=5;i++)b.reserveEffect(key(i),key(9),"turn","draft");
  assertEquals("MODEL_TURN_BUDGET_EXCEEDED",assertThrows(BankException.class,()->b.reserveEffect(key(6),key(9),"turn","draft")).code);
  for(int i=0;i<15;i++)b.reserve();
  assertEquals(first,b.reserveEffect(key(1),key(9),"turn","plan"));
  assertNotNull(b.reserveEffect(key(7),key(9),"other","plan"));
  assertEquals(21,b.snapshot().reserved());
 }
 @Test void recordBoundToOriginalTupleIsImmutable() throws Exception {
  var b=new DebugRequestBudget(temp.resolve("budget.json"),20);
  String id=b.reserveEffect(key(1),key(2),"turn","plan");
  var usage=new ObjectMapper().readTree("{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}");
  b.recordEffect(id,key(1),key(2),"turn","plan",true,usage);
  b.recordEffect(id,key(1),key(2),"turn","plan",true,usage);
  assertThrows(BankException.class,()->b.recordEffect(id,key(1),key(2),"other","plan",true,usage));
  assertThrows(BankException.class,()->b.recordEffect(id,key(1),key(2),"turn","plan",false,null));
  assertEquals(10,b.snapshot().promptTokens());
 }
 @Test void callerMayLowerLimitToOneIncludingRecoveredReservations(){
  var b=new DebugRequestBudget(temp.resolve("budget.json"),20);String first=b.reserveEffect(key(1),key(9),"turn","plan",1);
  assertEquals(first,b.reserveEffect(key(1),key(9),"turn","plan",1));
  assertEquals("MODEL_TURN_BUDGET_EXCEEDED",assertThrows(BankException.class,()->b.reserveEffect(key(2),key(9),"turn","draft",1)).code);
  assertEquals(1,b.snapshot().reserved());
 }
}
