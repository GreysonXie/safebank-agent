package cn.safebank;

import cn.safebank.service.*;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.common.*;
import cn.safebank.common.RiskTypes.RiskChannel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MySqlBankBoundaryTest {
    static ConfigurableApplicationContext app;static DriverManagerDataSource ds;static JdbcTemplate db;
    static ActionService actions;static AuthorizationGateService gate;static LocalBankExecutionService local;static MutableRiskClock clock;
    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        MutableRiskClock testClock(){return new MutableRiskClock(Instant.parse("2026-10-10T04:00:00Z"));}
    }
    static ConfigurableApplicationContext start(DriverManagerDataSource source) {
        return new SpringApplicationBuilder(Application.class,TimeConfig.class).profiles("mysql","mysql-test")
            .run("--server.port=0","--spring.datasource.url="+source.getUrl(),"--spring.main.banner-mode=off","--logging.level.root=ERROR");
    }
    @BeforeAll static void init() throws Exception {
        ds=(DriverManagerDataSource)MySqlTestSupport.fresh("boundary");app=start(ds);db=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/mysql/v07-synthetic.sql")).execute(ds);
        actions=app.getBean(ActionService.class);gate=app.getBean(AuthorizationGateService.class);local=app.getBean(LocalBankExecutionService.class);clock=app.getBean(MutableRiskClock.class);
    }
    @AfterAll static void close(){if(app!=null)app.close();}
    static cn.safebank.vo.ActionVO authorized(){
        var a=actions.previewTransfer("u1",new TransferPreviewRequestDTO("checking","zhang-a",5000));
        RiskRejectedException.value(gate.authorize("u1",a.id(),new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),true,null),RiskChannel.HTTP));return a;
    }
    @Test @Order(1) void repeatedSubmitKeepsBalancedLedger() {
        var a=authorized();long before=db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class);
        RiskRejectedException.value(gate.beginSubmission("u1",a.id(),RiskChannel.HTTP));assertEquals(ActionStatus.VERIFIED_SUCCESS,local.submit("u1",a.id()).status());local.submit("u1",a.id());
        assertEquals(before-5000,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,a.id()));assertEquals(0,db.queryForObject("SELECT SUM(amount_minor) FROM ledger WHERE action_id=?",Long.class,a.id()));
    }
    @Test @Order(2) void secondLedgerFailureRollsBackBalanceAndTransactions() {
        var a=authorized();RiskRejectedException.value(gate.beginSubmission("u1",a.id(),RiskChannel.HTTP));long before=db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class),target=db.queryForObject("SELECT balance_minor FROM accounts WHERE id='external-a'",Long.class);
        db.update("INSERT INTO ledger(id,action_id,account_id,amount_minor) VALUES('injected-conflict',?,'external-a',5000)",a.id());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->local.submit("u1",a.id()));
        assertEquals(before,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class));assertEquals(target,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='external-a'",Long.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM transactions WHERE id=?",Integer.class,a.id()+":debit"));assertEquals(ActionStatus.AUTHORIZED,actions.status("u1",a.id()).status());
        db.update("DELETE FROM ledger WHERE id='injected-conflict'");
    }
    @Test @Order(3) void expiresAtEqualsNowCannotSubmit() {
        var a=authorized();db.update("UPDATE actions SET expires_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant()),a.id());
        assertFalse(gate.authorize("u1",a.id(),new AuthorizationRequestDTO(a.payloadHash(),a.security().decisionId(),true,null),RiskChannel.HTTP).accepted());
        assertFalse(gate.beginSubmission("u1",a.id(),RiskChannel.HTTP).accepted());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,a.id()));
    }
    @Test @Order(4) void insufficientBalanceDoesNotPartiallyWrite() {
        var a=authorized();RiskRejectedException.value(gate.beginSubmission("u1",a.id(),RiskChannel.HTTP));long before=db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class);
        db.update("UPDATE accounts SET balance_minor=1 WHERE id='checking'");
        try{assertThrows(BankException.class,()->local.submit("u1",a.id()));assertEquals(1,db.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ledger WHERE action_id=?",Integer.class,a.id()));}
        finally{db.update("UPDATE accounts SET balance_minor=? WHERE id='checking'",before);}
    }
    @Test @Order(5) void originalUnknownKeepsReservedUsageAfterExpiry() {
        var a=authorized();gate.beginSubmission("u1",a.id(),RiskChannel.HTTP);
        app.getBean(cn.safebank.bank.FaultInjectingBankAdapter.class).configure("u1",a.id(),cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.NONE,cn.safebank.bank.FaultInjectingBankAdapter.FaultMode.LOOKUP_UNAVAILABLE);
        clock.set(clock.instant().plusSeconds(86400));var result=app.getBean(ActionExecutionService.class).observe("u1",a.id());assertEquals(a.id(),result.action().id());assertEquals(ActionStatus.UNKNOWN,result.action().status());assertEquals("SUBMITTED",db.queryForObject("SELECT state FROM risk_transfer_usage WHERE action_id=?",String.class,a.id()));
    }
    @Test @Order(6) void damagedSchemaStartupRefusesRepair() throws Exception {
        for(String damage:new String[]{"DROP TABLE risk_events","DROP TABLE agent_context_slots","ALTER TABLE agent_context_slots DROP FOREIGN KEY fk_agent_slot_turn","ALTER TABLE accounts DROP CHECK ck_accounts_1","ALTER TABLE ledger ENGINE=MyISAM"}) {
            var broken=(DriverManagerDataSource)MySqlTestSupport.fresh("broken");MySqlTestSupport.migration(broken).migrate();MySqlTestSupport.execute(broken,damage);
            assertThrows(Exception.class,()->{try(var unexpected=start(broken)){fail("Broken schema accepted: "+damage);}});
            if(damage.startsWith("DROP TABLE"))assertEquals(25,MySqlTestSupport.scalar(broken,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'"));
            if(damage.contains("MyISAM"))assertEquals(1,MySqlTestSupport.scalar(broken,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ledger' AND engine='MyISAM'"),"Never repair engine automatically");
        }
    }
    @Test @Order(7) void twoConnectionsSerializeRowLockAndVersionCas() throws Exception {
        db.update("INSERT INTO agent_conversations(id,user_id,version,intent,state,created_at,updated_at,expires_at) VALUES('cas','u1',1,'GENERAL','ACTIVE','2026-10-10','2026-10-10','2026-10-12')");
        try(var one=ds.getConnection();var two=ds.getConnection()) {
            one.setAutoCommit(false);two.setAutoCommit(false);
            try(var s=one.createStatement();var r=s.executeQuery("SELECT version FROM agent_conversations WHERE id='cas' FOR UPDATE")){assertTrue(r.next());}
            var entered=new java.util.concurrent.CountDownLatch(1);
            try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var waiting=pool.submit(()->{entered.countDown();try(var s=two.createStatement()){return s.executeUpdate("UPDATE agent_conversations SET version=2 WHERE id='cas' AND version=1");}});
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->waiting.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
                try(var s=one.createStatement()){assertEquals(1,s.executeUpdate("UPDATE agent_conversations SET version=2 WHERE id='cas' AND version=1"));}one.commit();assertEquals(0,waiting.get(5,java.util.concurrent.TimeUnit.SECONDS));two.commit();
            }finally{one.rollback();two.rollback();}
        }
        var mapper=app.getBean(AgentConversationMapper.class);var next=mapper.selectOwned("u1","cas");next.setVersion(3L);
        assertEquals(0,mapper.updateIfVersion("u1","cas",1,next));assertEquals(1,mapper.updateIfVersion("u1","cas",2,next));
    }
}

