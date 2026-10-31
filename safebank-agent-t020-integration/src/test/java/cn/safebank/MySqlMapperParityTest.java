package cn.safebank;

import cn.safebank.dao.*;
import cn.safebank.entity.*;
import cn.safebank.config.UtcOffsetDateTimeTypeHandler;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlMapperParityTest {
    static javax.sql.DataSource legacyOwnerFixture(String purpose) throws Exception {
        var ds=MySqlTestSupport.fresh(purpose);MySqlTestSupport.migration(ds).migrate();
        MySqlTestSupport.execute(ds,"INSERT INTO actions(id,user_id,kind,payload,payload_hash,status,expires_at) VALUES('legacy','u1','TRANSFER','{}','hash','AWAITING_AUTH','2027-01-01')");return ds;
    }
    @Test void unknownFaultOwnerIsRejectedWithoutChangingAnyOriginalField() throws Exception {
        var ds=legacyOwnerFixture("nullfault");
        MySqlTestSupport.execute(ds,"INSERT INTO bank_faults(action_id,user_id,submit_mode,lookup_mode) VALUES('legacy',NULL,'BEFORE_SUBMIT_FAILURE','LOOKUP_UNAVAILABLE')");
        var original=canonicalRows(ds,"bank_faults");
        try(var session=factory(ds).openSession(true)){
            var row=new BankFaultEntity();row.setActionId("legacy");row.setUserId("u1");row.setSubmitMode("NONE");row.setLookupMode("NONE");
            assertEquals(0,session.getMapper(BankFaultMapper.class).upsert(row));
        }assertEquals(original,canonicalRows(ds,"bank_faults"));
    }
    @Test void unknownObservationOwnerIsRejectedWithoutChangingAnyOriginalField() throws Exception {
        var ds=legacyOwnerFixture("nullobs");
        MySqlTestSupport.execute(ds,"INSERT INTO execution_observations(action_id,user_id,state,receipt_id,error_code,observed_at) VALUES('legacy',NULL,'UNKNOWN','original','KEEP','2026-10-10 01:02:03.123456')");
        var original=canonicalRows(ds,"execution_observations");
        try(var session=factory(ds).openSession(true)){
            var row=new ExecutionObservationEntity();row.setActionId("legacy");row.setUserId("u1");row.setState("VERIFIED_SUCCESS");row.setReceiptId("new");row.setObservedAt(OffsetDateTime.parse("2026-10-10T12:00:00Z"));
            assertEquals(0,session.getMapper(ExecutionObservationMapper.class).upsert(row));
        }assertEquals(original,canonicalRows(ds,"execution_observations"));
    }
    @Test void syntheticSeedMatchesSourceAndCannotBeReimported() throws Exception {
        var ds=MySqlTestSupport.fresh("seed");MySqlTestSupport.migration(ds).migrate();
        var resource=new org.springframework.core.io.ClassPathResource("db/fixtures/mysql/v07-synthetic.sql");
        assertTrue(resource.exists(),"Explicit MySQL synthetic fixture required");
        var seed=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(resource);seed.execute(ds);
        assertEquals(5,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM accounts"));
        assertEquals(5154480,MySqlTestSupport.scalar(ds,"SELECT balance_minor FROM accounts WHERE id='checking'"));
        assertEquals(3,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM recipients"));
        assertEquals(5,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM mandates"));
        assertEquals(2,MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM transactions WHERE category='内部转账' AND booked_on BETWEEN '2026-09-01' AND '2026-09-30'"));
        assertTrue(MySqlTestSupport.scalar(ds,"SELECT COUNT(*) FROM transactions WHERE category='退款' AND amount_minor>0")>0);
        var h2=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:seed_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql"),new org.springframework.core.io.ClassPathResource("schema-risk.sql"),new org.springframework.core.io.ClassPathResource("schema-context.sql"),new org.springframework.core.io.ClassPathResource("risk-demo-data.sql")).execute(h2);
        for(var table:MySqlSchemaComparisonTest.structure(h2).keySet()) {
            assertEquals(canonicalRows(h2,table),canonicalRows(ds,table),"All original table values: "+table);
        }
        var mappings=Map.ofEntries(Map.entry("accounts","Account"),Map.entry("recipients","Recipient"),Map.entry("transactions","Transaction"),Map.entry("mandates","Mandate"),Map.entry("actions","Action"),Map.entry("ledger","LedgerEntry"),Map.entry("schedules","Schedule"),Map.entry("occurrences","ScheduleOccurrence"),Map.entry("audit","AuditEvent"),Map.entry("execution_observations","ExecutionObservation"),Map.entry("bank_faults","BankFault"),Map.entry("workflows","Workflow"),Map.entry("workflow_steps","WorkflowStep"),Map.entry("risk_user_guards","RiskUserGuard"),Map.entry("risk_decisions","RiskDecision"),Map.entry("verification_challenges","VerificationChallenge"),Map.entry("action_authorizations","ActionAuthorization"),Map.entry("risk_transfer_usage","RiskTransferUsage"),Map.entry("schedule_authorizations","ScheduleAuthorization"),Map.entry("risk_events","RiskEvent"),Map.entry("agent_conversations","AgentConversation"),Map.entry("agent_turn_receipts","AgentTurnReceipt"),Map.entry("agent_context_evidence","AgentContextEvidence"),Map.entry("agent_context_slots","AgentContextSlot"));
        var factory=factory(ds);var config=factory.getConfiguration();
        for(var entry:mappings.entrySet()) {
            Class<?> type=Class.forName("cn.safebank.entity."+entry.getValue()+"Entity");
            var resultMap=config.getResultMapNames().stream().filter(n->n.contains(".")).map(config::getResultMap).filter(r->r.getType()==type).findFirst().orElseThrow();
            var mapped=new org.apache.ibatis.mapping.MappedStatement.Builder(config,"parity."+entry.getKey(),new org.apache.ibatis.builder.StaticSqlSource(config,"SELECT * FROM "+entry.getKey()),org.apache.ibatis.mapping.SqlCommandType.SELECT).resultMaps(List.of(resultMap)).build();config.addMappedStatement(mapped);
            try(var session=factory.openSession()){assertEquals(canonicalRows(ds,entry.getKey()).size(),session.selectList(mapped.getId()).size());}
        }
        assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class,()->seed.execute(ds));
    }
    static List<String> canonicalRows(javax.sql.DataSource ds,String table) {
        var rows=new org.springframework.jdbc.core.JdbcTemplate(ds).queryForList("SELECT * FROM "+table);
        return rows.stream().map(row->{var normal=new TreeMap<String,String>();row.forEach((k,v)->normal.put(k.toLowerCase(Locale.ROOT),v==null?null:v.toString()));return normal.toString();}).sorted().toList();
    }
    static SqlSessionFactory factory(javax.sql.DataSource ds) throws Exception {
        var config=new Configuration();config.setDatabaseId("mysql");config.setMapUnderscoreToCamelCase(true);
        config.getTypeHandlerRegistry().register(OffsetDateTime.class,new UtcOffsetDateTimeTypeHandler());
        var bean=new SqlSessionFactoryBean();bean.setDataSource(ds);bean.setConfiguration(config);
        bean.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));return bean.getObject();
    }
    @Test void upsertsUpdateSameOwnerAndRejectAnotherOwner() throws Exception {
        var ds=MySqlTestSupport.fresh("mapper");MySqlTestSupport.migration(ds).migrate();
        MySqlTestSupport.execute(ds,"INSERT INTO actions(id,user_id,kind,payload,payload_hash,status,expires_at) VALUES('a','u1','TRANSFER','{}','hash','AWAITING_AUTH','2027-01-01')");
        try(var session=factory(ds).openSession(true)) {
            var faults=session.getMapper(BankFaultMapper.class);var row=new BankFaultEntity();row.setActionId("a");row.setUserId("u1");row.setSubmitMode("NONE");row.setLookupMode("NONE");
            assertEquals(1,faults.upsert(row));row.setLookupMode("LOOKUP_UNAVAILABLE");assertEquals(1,faults.upsert(row));assertEquals(1,faults.upsert(row));
            row.setUserId("u2");assertEquals(0,faults.upsert(row));assertEquals("LOOKUP_UNAVAILABLE",faults.selectOwned("u1","a").getLookupMode());
            var observations=session.getMapper(ExecutionObservationMapper.class);var o=new ExecutionObservationEntity();o.setActionId("a");o.setUserId("u1");o.setState("PENDING");o.setObservedAt(OffsetDateTime.parse("2026-10-10T01:02:03.123456+08:00"));
            assertEquals(1,observations.upsert(o));o.setState("UNKNOWN");assertEquals(1,observations.upsert(o));assertEquals(1,observations.upsert(o));
            o.setUserId("u2");o.setState("VERIFIED_SUCCESS");assertEquals(0,observations.upsert(o));assertEquals("UNKNOWN",observations.selectOwned("u1","a").getState());
        }
    }
    @Test void longRawJsonUnicodeAndNullArePreservedByMappedRead() throws Exception {
        var ds=MySqlTestSupport.fresh("text");MySqlTestSupport.migration(ds).migrate();
        String raw="{\"商户\":\""+"合成中文".repeat(4000)+"\", \"值\": null}";
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var c=ds.getConnection();var s=c.prepareStatement("INSERT INTO actions(id,user_id,kind,payload,payload_hash,status,expires_at) VALUES('raw','u1','TRANSFER',?,?,'AWAITING_AUTH','2027-01-01')")) {s.setString(1,raw);s.setString(2,hash);s.executeUpdate();}
        try(var session=factory(ds).openSession(true)) {var action=session.getMapper(ActionMapper.class).selectOwned("u1","raw");assertEquals(raw,action.getPayload());assertEquals(hash,action.getPayloadHash());assertNull(action.getReceiptId());}
    }
}
