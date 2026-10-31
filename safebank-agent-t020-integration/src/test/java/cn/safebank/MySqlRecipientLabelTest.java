package cn.safebank;
import cn.safebank.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlRecipientLabelTest {
 @Test void displayedAccountLabelResolvesOnlyOwnedRecipientAndPreparesWithoutAuthorizing(){
  var fixture=new MySqlAgentPreviewTest();fixture.start();
  try {
   var db=MySqlAgentPreviewTest.db;var recipients=MySqlAgentPreviewTest.app.getBean(RecipientService.class);
   String label=recipients.search("u1","同学").getFirst().label();
   assertEquals(1,recipients.search("u1",label).size());assertTrue(recipients.search("u2",label).isEmpty());
   int auth=db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class),ledger=db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class);
   String cid=MySqlAgentPreviewTest.contexts.create("u1"),grant=MySqlAgentPreviewTest.token(cid);
   var receipt=MySqlAgentPreviewTest.caps.call(grant,MySqlAgentPreviewTest.previewRequest("label-choice","transfer_preview",Map.of("sourceQuery","日常账户","recipientQuery",label,"amountMinor",10000)));
   assertTrue(receipt.data().has("pendingAction"));assertEquals("AWAITING_AUTH",receipt.data().path("pendingAction").path("status").asText());
   assertEquals("100.00元",receipt.facts().get("preview_transfer.amount_minor").display());
   assertEquals(auth,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(ledger,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
  } finally {fixture.close();}
 }
}
