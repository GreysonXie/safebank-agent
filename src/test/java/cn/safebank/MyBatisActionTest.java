package cn.safebank;
import cn.safebank.service.ActionService;
import cn.safebank.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-action;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class MyBatisActionTest extends BankTestFixture {
    @Autowired ActionService actions;
    @Autowired ObjectMapper json;
    @Test void previewAndAuthorizationAreTypedWithoutMovingFunds() {
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));
        var serialized=json.valueToTree(a);
        assertEquals(50000,serialized.path("amount_minor").asLong());assertFalse(serialized.has("parameters"));
        assertTrue(serialized.has("receipt_id"));assertTrue(serialized.path("receipt_id").isNull());
        assertEquals("CONFLICT",assertThrows(BankException.class,()->bank.authorize("u1",a.id(),"bad-hash")).code);
        assertEquals("NOT_FOUND",assertThrows(BankException.class,()->actions.status("u2",a.id())).code);
        String originalPayload=db.queryForObject("SELECT payload FROM actions WHERE id=?",String.class,a.id());
        bank.authorize("u1",a.id(),a.payloadHash());
        assertEquals(a.payloadHash(),actions.status("u1",a.id()).payloadHash());
        assertEquals(originalPayload,db.queryForObject("SELECT payload FROM actions WHERE id=?",String.class,a.id()));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void expiredPreviewAndRepeatedTerminalAuthorizationAreRejected() {
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",50000));
        actions.cancel("u1",a.id());
        assertEquals("CONFLICT",assertThrows(BankException.class,()->bank.authorize("u1",a.id(),a.payloadHash())).code);
        var b=actions.previewRevoke("u1",new MandatePreviewRequestDTO("music"));
        db.update("UPDATE actions SET expires_at=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE id=?",b.id());
        assertEquals("AUTH_EXPIRED",assertThrows(BankException.class,()->bank.authorize("u1",b.id(),b.payloadHash())).code);
    }
}
