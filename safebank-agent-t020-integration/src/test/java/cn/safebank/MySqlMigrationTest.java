package cn.safebank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;

@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlMigrationTest {
    @Test void allTablesAndOwnershipConstraintsMigrateOnlyOnce() throws Exception {
        var ds=MySqlTestSupport.fresh("schema");
        var flyway=MySqlTestSupport.migration(ds);
        flyway.migrate();
        assertEquals(26,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'"));
        assertEquals(2,flyway.info().applied().length);
        assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(6,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('fk_agent_turn_conversation','fk_agent_evidence_conversation','fk_agent_evidence_turn','fk_agent_slot_conversation','fk_agent_slot_turn','fk_agent_slot_evidence')"));
        assertEquals(6,MySqlTestSupport.scalar(ds,"SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_context_evidence' AND column_name='observed_at'"));
    }
    @Test void constraintsAndBinaryIdentifiersAreActuallyEnforced() throws Exception {
        var ds=MySqlTestSupport.fresh("checks");MySqlTestSupport.migration(ds).migrate();
        MySqlTestSupport.execute(ds,"INSERT INTO accounts(id,user_id,label,balance_minor) VALUES('CaseId','u1','合成',1),('caseid','u1','合成',2),('CaseId ','u1','合成',3)");
        assertEquals(3,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM accounts"));
        assertThrows(SQLException.class,()->MySqlTestSupport.execute(ds,"INSERT INTO accounts(id,user_id,label,balance_minor) VALUES('bad','u1','合成',-1)"));
        assertThrows(SQLException.class,()->MySqlTestSupport.execute(ds,"INSERT INTO agent_context_slots(conversation_id,user_id,slot_name,value_json,provenance,context_version,source_turn_id,updated_at) VALUES('missing','u1','month','{}','USER_EXPLICIT',1,'missing','2026-10-10 00:00:00')"));
    }
}
