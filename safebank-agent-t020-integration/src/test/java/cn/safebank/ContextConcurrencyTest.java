package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.ChatRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextConcurrency;DB_CLOSE_DELAY=-1")
class ContextConcurrencyTest {
    @Autowired ContextService contexts;
    @Test void activeTurnBlocksSecondRequestAndReplayReturnsProcessing(){
        var r=contexts.begin("u1",new ChatRequestDTO("查账单",5,null,"first",null));var lease=r.lease();
        assertTrue(contexts.begin("u1",new ChatRequestDTO("查账单",5,lease.conversationId(),"first",1L)).processing());
        assertEquals("CONFLICT",assertThrows(BankException.class,()->contexts.begin("u1",new ChatRequestDTO("改金额",5,lease.conversationId(),"second",1L))).code);
        contexts.fail(lease,"MODEL_UNAVAILABLE");
        assertNotNull(contexts.begin("u1",new ChatRequestDTO("查账单",5,lease.conversationId(),"third",1L)).lease());
    }
}
