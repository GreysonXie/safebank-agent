package cn.safebank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:riskPersistence;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class RiskPersistenceTest extends BankTestFixture {
    @Autowired ApplicationContext context;
    @Test void schemaCanRestartWithoutRepeatingMigration() throws Exception {
        assertEquals(28,db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",Integer.class));
        assertTrue(context.containsBean("riskSchemaInitializer"),"risk schema startup gate is missing");
        Object initializer=context.getBean("riskSchemaInitializer");
        assertDoesNotThrow(()->initializer.getClass().getMethod("initialize").invoke(initializer));
        assertEquals(28,db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",Integer.class));
    }
    @Test void incompleteSchemaIsRejected() {
        assertTrue(context.containsBean("riskSchemaInitializer"),"risk schema startup gate is missing");
        db.execute("DROP TABLE risk_events");
        Object initializer=context.getBean("riskSchemaInitializer");
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->initializer.getClass().getMethod("initialize").invoke(initializer));
        assertInstanceOf(IllegalStateException.class,failure.getCause());
    }
    @Test void crossUserGrantCannotAttachToAnotherAction() {
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='ACTION_AUTHORIZATIONS'",Integer.class)>0,"authorization storage is missing");
        String id=bank.preview("u1","checking","zhang-a",60000).get("id").toString();
        db.update("MERGE INTO risk_user_guards(user_id,updated_at) KEY(user_id) VALUES('u1',CURRENT_TIMESTAMP),('u2',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO risk_decisions(id,action_id,user_id,revision,policy_version,risk_level,required_assurance,reason_code,business_date,amount_minor,effective_minor,projected_minor,payload_hash,created_at,valid_until) SELECT 'd',id,user_id,2,'v1','YELLOW','CONFIRMED','CONFIRM',CURRENT_DATE,60000,0,60000,payload_hash,CURRENT_TIMESTAMP,DATEADD('MINUTE',5,CURRENT_TIMESTAMP) FROM actions WHERE id=?",id);
        assertThrows(DataIntegrityViolationException.class,()->db.update("INSERT INTO action_authorizations(id,action_id,user_id,decision_id,payload_hash,policy_version,business_date,method,assurance,status,authorized_at,expires_at) VALUES('g',?,'u2','d','hash','v1',CURRENT_DATE,'USER_CONFIRM','CONFIRMED','ACTIVE',CURRENT_TIMESTAMP,DATEADD('MINUTE',5,CURRENT_TIMESTAMP))",id));
    }
}

