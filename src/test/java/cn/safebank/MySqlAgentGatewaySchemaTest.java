package cn.safebank;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlAgentGatewaySchemaTest {
    @Test void gatewayAddsOwnedProtocolTableWithoutChangingBaseMigrations() throws Exception {
        var ds=MySqlTestSupport.fresh("gatewayddl");
        var migration=Flyway.configure().dataSource(ds).locations("classpath:db/migration/mysql","classpath:db/migration/mysql-agent").load();
        migration.migrate();
        assertEquals(4,migration.info().applied().length,"Gateway protocol migration must be present");
        assertEquals(28,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'"));
        assertEquals(1,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_gateway_turn'"));
        assertEquals(2,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='agent_mandate_query_receipts' AND constraint_type='FOREIGN KEY'"));
        assertEquals(1,MySqlTestSupport.scalar(ds,"SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='agent_mandate_query_receipts' AND index_name='ix_mandate_receipt_owner'"));
        assertEquals(0,migration.migrate().migrationsExecuted);
    }
}
