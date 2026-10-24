package cn.safebank;

import cn.safebank.controller.ModelBudgetController;
import cn.safebank.model.DebugRequestBudget;
import cn.safebank.service.ModelBudgetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpSession;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ModelBudgetViewTest {
 @TempDir Path temp;
 @Test void summaryRequiresSessionAndNeverReturnsIndividualReservations() {
  var budget=new DebugRequestBudget(temp.resolve("usage.json"),100);
  budget.reserveEffect("1".repeat(64),"2".repeat(64),"private-turn","plan");
  var env=new MockEnvironment().withProperty("bank.agent.model-mode","deepseek");
  var controller=new ModelBudgetController(new ModelBudgetService(budget,env));
  var session=new MockHttpSession();
  assertEquals("SESSION_REQUIRED",assertThrows(BankException.class,()->controller.read(session)).code);
  session.setAttribute("user","u1");var view=controller.read(session);
  assertTrue(view.applies());assertEquals(1,view.used());assertEquals(99,view.remaining());
  var json=new ObjectMapper().valueToTree(view);var fields=new java.util.HashSet<String>();json.fieldNames().forEachRemaining(fields::add);
  assertEquals(Set.of("applies","used","remaining","limit"),fields);
  env.setProperty("bank.agent.model-mode","mock");assertFalse(controller.read(session).applies());
 }
}
