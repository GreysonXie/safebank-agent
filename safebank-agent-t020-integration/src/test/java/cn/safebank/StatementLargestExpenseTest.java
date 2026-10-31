package cn.safebank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatementLargestExpenseTest {
 private final ObjectMapper json=new ObjectMapper();
 private Map<String,Object> row(String id,String category,long amount){return Map.of("id",id,"merchant",id+"商户","category",category,"amount_minor",amount,"booked_on","2026-09-02");}
 private GroundedAnswer scope(List<Map<String,Object>> rows){
  return new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","transactions",rows,"anomaly_candidates",List.of(),"categories",Map.of(),"expense_minor",0,"income_minor",0))));
 }
 @Test void findsLargestBelowThresholdAndExcludesRefundAndInternalTransfer(){
  var rows=List.of(row("refund","退款",900000),row("internal","内部转账",-800000),row("small","餐饮",-2800),row("largest","日用品",-149900));
  var saved=json.valueToTree(rows);var answer=scope(rows);var facts=answer.factCatalog();String p="statement_2026_09.analysis.largest_expenses";
  assertTrue(facts.containsKey(p+".0.merchant"));
  assertEquals(new BigDecimal("149900"),facts.get(p+".0.spending_minor").number());
  assertTrue(facts.get(p+".0.merchant").display().contains("largest商户"));
  assertEquals(BigDecimal.ONE,facts.get(p+".count").number());assertEquals(saved,json.valueToTree(rows));
  String text=answer.render(json.valueToTree(Map.of("kind","analysis","answer","最大支出对应{{"+p+".0.merchant}}，支出{{"+p+".0.spending_minor}}。用途仍需核对凭证。"))).text();
  assertTrue(text.contains("1,499.00"));assertFalse(text.contains("refund商户"));
 }
 @Test void allTiesArePreservedWithStableSameRowReferences(){
  var facts=scope(List.of(row("a","餐饮",-5000),row("b","日用品",-5000),row("c","餐饮",-3000))).factCatalog();String p="statement_2026_09.analysis.largest_expenses";
  assertTrue(facts.containsKey(p+".count"));assertEquals(new BigDecimal("2"),facts.get(p+".count").number());
  assertTrue(facts.get(p+".0.merchant").display().contains("a商户"));assertTrue(facts.get(p+".1.merchant").display().contains("b商户"));
 }
 @Test void emptyOrIncomeOnlyStatementDoesNotInventExpense(){
  for(var rows:List.of(List.<Map<String,Object>>of(),List.of(row("refund","退款",1000),row("internal","内部转账",-10000)))){
   var facts=scope(rows).factCatalog();String p="statement_2026_09.analysis.largest_expenses";
   assertTrue(facts.containsKey(p+".count"));assertEquals(BigDecimal.ZERO,facts.get(p+".count").number());assertFalse(facts.containsKey(p+".0.spending_minor"));
  }
 }
}
