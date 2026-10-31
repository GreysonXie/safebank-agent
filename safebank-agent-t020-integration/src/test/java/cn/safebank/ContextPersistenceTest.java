package cn.safebank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextPersistence;DB_CLOSE_DELAY=-1")
class ContextPersistenceTest {
    @Autowired JdbcTemplate db;
    @Test void fourOwnedContextTablesAreInitialized(){
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('AGENT_CONVERSATIONS','AGENT_CONTEXT_SLOTS','AGENT_CONTEXT_EVIDENCE','AGENT_TURN_RECEIPTS')",Integer.class));
    }
    @Test void databaseRejectsForeignUserTurnAndDuplicateRequest(){
        db.update("INSERT INTO agent_conversations(id,user_id,created_at,updated_at,expires_at) VALUES('c1','u1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,DATEADD('DAY',7,CURRENT_TIMESTAMP))");
        assertThrows(DataIntegrityViolationException.class,()->db.update("INSERT INTO agent_turn_receipts(id,conversation_id,user_id,request_id,request_hash,input_version,state,created_at) VALUES('t-wrong','c1','u2','r1','hash',1,'PROCESSING',CURRENT_TIMESTAMP)"));
        db.update("INSERT INTO agent_turn_receipts(id,conversation_id,user_id,request_id,request_hash,input_version,state,created_at) VALUES('t1','c1','u1','r1','hash',1,'PROCESSING',CURRENT_TIMESTAMP)");
        assertThrows(DataIntegrityViolationException.class,()->db.update("INSERT INTO agent_turn_receipts(id,conversation_id,user_id,request_id,request_hash,input_version,state,created_at) VALUES('t2','c1','u1','r1','hash',1,'PROCESSING',CURRENT_TIMESTAMP)"));
        assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE agent_conversations SET version=0 WHERE id='c1'"));
        assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE agent_conversations SET state='AUTHORIZED' WHERE id='c1'"));
    }
}
