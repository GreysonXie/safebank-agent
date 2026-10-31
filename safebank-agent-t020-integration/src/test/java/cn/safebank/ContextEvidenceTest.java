package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.ChatRequestDTO;
import cn.safebank.common.ContextContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextEvidence;DB_CLOSE_DELAY=-1")
class ContextEvidenceTest {
    @Autowired ContextService contexts; @Autowired ContextEvidenceService evidence; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Test void statementSnapshotsAreBoundedAndTamperingIsRejected(){
        var l=contexts.begin("u1",new ChatRequestDTO("查询",5,null,"evidence",null)).lease();
        for(int m=1;m<=5;m++)evidence.store(l,List.of(new EvidenceInput("get_statement:2026-0"+m,"get_statement",null,json.valueToTree(Map.of("month","2026-0"+m)),Instant.now(),null)));
        assertEquals(4,evidence.load("u1",l.conversationId()).size());
        db.update("UPDATE agent_context_evidence SET snapshot_json='{}' WHERE conversation_id=? AND snapshot_json IS NOT NULL",l.conversationId());
        assertEquals("CONFLICT",assertThrows(BankException.class,()->evidence.load("u1",l.conversationId())).code);
    }
}
