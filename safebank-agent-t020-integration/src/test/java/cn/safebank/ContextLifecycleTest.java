package cn.safebank;
import cn.safebank.service.ContextService;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.common.ContextContracts.*;
import static cn.safebank.common.ContextTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextLifecycle;DB_CLOSE_DELAY=-1")
class ContextLifecycleTest {
    @Autowired ContextService contexts; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    ChatRequestDTO request(String c,String r,Long v,String text){return new ChatRequestDTO(text,5,c,r,v);}
    @Test void memorySurvivesSixTurnsAndReplayDoesNotCreateNewTurn(){
        var first=contexts.begin("u1",request(null,"r1",null,"分析九月"));var lease=first.lease();
        var s=contexts.snapshot("u1",lease.conversationId());
        var slots=Map.of(SlotName.PRIMARY_MONTH,new SlotValue(json.valueToTree("2026-09"),SlotProvenance.USER_EXPLICIT,lease.turnId(),null));
        contexts.complete(lease,new ContextCommit(new ContextSnapshot(s.conversationId(),s.version(),Intent.STATEMENT_ANALYSIS,ConversationState.ACTIVE,slots,null,List.of(),List.of()),List.of(),Map.of("text","九月账单")));
        for(int i=2;i<=6;i++){
            s=contexts.snapshot("u1",lease.conversationId());var next=contexts.begin("u1",request(s.conversationId(),"r"+i,s.version(),"继续"));
            contexts.complete(next.lease(),new ContextCommit(s,List.of(),Map.of("text","第"+i+"轮")));
        }
        s=contexts.snapshot("u1",lease.conversationId());assertEquals("2026-09",s.slots().get(SlotName.PRIMARY_MONTH).value().asText());assertTrue(s.displayHistory().size()<=4);
        var replay=contexts.begin("u1",request(s.conversationId(),"r1",1L,"分析九月"));assertNotNull(replay.replayResponse());assertEquals("九月账单",replay.replayResponse().get("text"));
        assertEquals("CONFLICT",assertThrows(BankException.class,()->contexts.begin("u1",request(lease.conversationId(),"r1",1L,"改文本"))).code);
    }
    @Test void expiryClearsPayloadAndCannotReplay(){
        var start=contexts.begin("u1",request(null,"expiry",null,"九月"));var lease=start.lease();
        contexts.complete(lease,new ContextCommit(contexts.snapshot("u1",lease.conversationId()),List.of(),Map.of("text","旧回答")));
        db.update("UPDATE agent_conversations SET expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=?",lease.conversationId());
        assertEquals("CONTEXT_EXPIRED",assertThrows(BankException.class,()->contexts.read("u1",lease.conversationId())).code);
        assertNull(db.queryForObject("SELECT response_json FROM agent_turn_receipts WHERE id=?",String.class,lease.turnId()));
        assertEquals("NOT_FOUND",assertThrows(BankException.class,()->contexts.read("u2",lease.conversationId())).code);
    }
}
