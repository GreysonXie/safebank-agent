package cn.safebank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskAgent;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class RiskAgentBoundaryTest extends BankTestFixture {
    @Test void modelCanOnlyPrepareQueryOrPaymentCard() throws Exception {
        var json=new ObjectMapper();var result=json.valueToTree(gateway.execute("u1","list_mandates",json.createObjectNode()));assertEquals("QUERY_MANDATES",result.path("pendingAction").path("kind").asText());assertFalse(result.has("data"));assertFalse(result.toString().contains("音乐服务"));
        for(String tool:new String[]{"authorize","submit","verify","get_demo_code","set_risk"})assertEquals("PERMISSION_DENIED",assertThrows(BankException.class,()->gateway.execute("u1",tool,json.valueToTree(Map.of("userId","u2","riskLevel","GREEN")))).code);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
}
