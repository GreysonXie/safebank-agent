package cn.safebank;

import cn.safebank.dto.*;
import cn.safebank.service.*;
import cn.safebank.vo.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:workspace;DB_CLOSE_DELAY=-1")
class WorkspaceManagementTest {
    @Autowired UserManagementService users;
    @Autowired CardManagementService cards;
    @Autowired StatementService statements;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @BeforeEach void reset(){jdbc.execute("DROP ALL OBJECTS");new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("schema-risk.sql"),new ClassPathResource("data.sql")).execute(dataSource);}
    private CardChangeDTO apply(){return new CardChangeDTO("APPLY",null,"checking","日常演示卡","VIRTUAL",500000L,2000000L,true,false);}
    private CardChangeDTO change(String id,String kind){return new CardChangeDTO(kind,id,null,null,null,null,null,null,null);}
    private CardOperationVO execute(CardChangeDTO request){var p=cards.preview("u1",request);return cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true));}
    private BankCardVO issued(){return execute(apply()).card();}
    @Test void julyAugustSeptemberAreAvailableAndTotalsRemainConsistent(){
        assertEquals(java.util.List.of("2026-09","2026-08","2026-07"),statements.months("u1"));
        assertTrue(statements.statement("u1","2026-07").transactions().size()>=8);
        assertTrue(statements.statement("u1","2026-08").transactions().size()>=10);
        assertEquals(340300,statements.statement("u1","2026-09").expenseMinor());
        assertTrue(statements.months("u2").isEmpty());
    }
    @Test void contactCreateEditAndSavingsTargetsShareExistingRecipient(){
        var c=users.create("u1",new ManagedUserRequestDTO("李明","13812345678","MALE","家庭储蓄",true,null,25000));
        assertEquals(25000,c.balanceMinor());assertTrue(c.savingsTarget());assertFalse(c.ownAccount());
        var edited=users.update("u1",c.id(),new ManagedUserRequestDTO("李明新","13812345679","UNDISCLOSED","家人",false,c.accountId(),0));
        assertEquals("李明新",edited.name());assertFalse(edited.savingsTarget());
        assertEquals("李明新",jdbc.queryForObject("SELECT name FROM recipients WHERE id=?",String.class,c.id()));
        assertThrows(BankException.class,()->users.update("u2",c.id(),new ManagedUserRequestDTO("他人","","UNKNOWN","",false,c.accountId(),0)));
    }
    @Test void existingAccountCanBeLinkedButBalanceCannotBeChanged(){
        var c=users.create("u1",new ManagedUserRequestDTO("备用储蓄","","UNKNOWN","本人",true,"travel",0));
        assertTrue(c.ownAccount());assertEquals(5820000,c.balanceMinor());
        assertThrows(BankException.class,()->users.create("u1",new ManagedUserRequestDTO("越权","","UNKNOWN","",false,"other",0)));
        assertThrows(BankException.class,()->users.update("u1",c.id(),new ManagedUserRequestDTO("改余额","","UNKNOWN","",true,"travel",100)));
    }
    @Test void profileIsEditableAndIsolated(){
        users.saveProfile("u1",new UserProfileRequestDTO("本人演示","13800000000","FEMALE"));
        assertEquals("本人演示",users.profile("u1").name());assertEquals("演示用户",users.profile("u2").name());
    }
    @Test void applicationNeedsConfirmationAndMatchingHash(){
        var p=cards.preview("u1",apply());assertTrue(cards.list("u1").isEmpty());
        assertThrows(BankException.class,()->cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),false)));
        assertThrows(BankException.class,()->cards.confirm("u1",p.id(),new CardConfirmDTO("wrong",true)));
        assertTrue(cards.list("u1").isEmpty());
    }
    @Test void repeatedConfirmationReturnsOriginalReceiptWithoutSecondCard(){
        var p=cards.preview("u1",apply());
        assertEquals(java.time.ZoneOffset.UTC,p.createdAt().getOffset());
        assertEquals(0,p.createdAt().getNano()%1000);
        assertEquals(p,cards.get("u1",p.id()));
        var first=cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true));
        execute(change(first.card().id(),"LOCK"));
        var again=cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true));
        assertEquals(first.receiptId(),again.receiptId());assertEquals("ACTIVE",again.card().status());assertEquals(1,cards.list("u1").size());
        assertEquals(first,again);
    }
    @Test void concurrentConfirmationIssuesExactlyOneCard() throws Exception {
        var p=cards.preview("u1",apply());
        try(var pool=Executors.newFixedThreadPool(4)){
            var jobs=new java.util.ArrayList<Future<CardOperationVO>>();
            for(int i=0;i<4;i++)jobs.add(pool.submit(()->cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true))));
            var receipts=new java.util.HashSet<String>();for(var job:jobs)receipts.add(job.get().receiptId());assertEquals(1,receipts.size());
        }
        assertEquals(1,cards.list("u1").size());
    }
    @Test void applicationsOnDifferentAccountsShareTheUserCardLimit() throws Exception {
        for(int i=0;i<7;i++)issued();
        var first=cards.preview("u1",apply());
        var second=cards.preview("u1",new CardChangeDTO("APPLY",null,"travel","旅游演示卡","DEBIT",500000L,2000000L,true,false));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var jobs=new java.util.ArrayList<Future<Boolean>>();
            for(var p:java.util.List.of(first,second))jobs.add(pool.submit(()->{
                ready.countDown();start.await();
                try{cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true));return true;}
                catch(BankException e){assertEquals("LIMIT_EXCEEDED",e.code);return false;}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            int completed=0;for(var job:jobs)if(job.get(10,TimeUnit.SECONDS))completed++;
            assertEquals(1,completed);
        }
        assertEquals(8,cards.list("u1").size());
    }
    @Test void ownerScopeAppliesToCardsAndOperations(){
        var c=issued();assertTrue(cards.list("u2").isEmpty());assertTrue(cards.history("u2").isEmpty());
        assertThrows(BankException.class,()->cards.preview("u2",change(c.id(),"LOCK")));
        var p=cards.preview("u1",change(c.id(),"LOCK"));assertThrows(BankException.class,()->cards.get("u2",p.id()));
        assertThrows(BankException.class,()->cards.confirm("u2",p.id(),new CardConfirmDTO(p.payloadHash(),true)));
    }
    @Test void stalePreviewExpiresAndCannotOverwriteLatestCardSettings(){
        var c=issued();var p=cards.preview("u1",change(c.id(),"LOCK"));execute(change(c.id(),"LOCK"));
        assertThrows(BankException.class,()->cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true)));
        var expired=cards.preview("u1",change(c.id(),"UNLOCK"));jdbc.update("UPDATE card_operations SET expires_at=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE id=?",expired.id());
        assertThrows(BankException.class,()->cards.confirm("u1",expired.id(),new CardConfirmDTO(expired.payloadHash(),true)));
    }
    @Test void lockUnlockAndLossRespectStateAndNeverChangeBalance(){
        var c=issued();assertEquals("LOCKED",execute(change(c.id(),"LOCK")).card().status());
        assertEquals("ACTIVE",execute(change(c.id(),"UNLOCK")).card().status());
        assertEquals("LOST",execute(change(c.id(),"REPORT_LOST")).card().status());
        assertThrows(BankException.class,()->cards.preview("u1",change(c.id(),"UNLOCK")));
        assertEquals(1243000,jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id='checking'",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ledger",Integer.class));
    }
    @Test void limitsAndChannelSwitchesAreVersionedAndValidated(){
        var c=issued();assertThrows(BankException.class,()->cards.preview("u1",new CardChangeDTO("UPDATE_LIMITS",c.id(),null,null,null,300000L,200000L,null,null)));
        var limits=execute(new CardChangeDTO("UPDATE_LIMITS",c.id(),null,null,null,100000L,800000L,null,null)).card();
        assertEquals(100000,limits.perTransactionLimitMinor());assertEquals(2,limits.version());
        var controls=execute(new CardChangeDTO("UPDATE_CONTROLS",c.id(),null,null,null,null,null,false,true)).card();
        assertFalse(controls.onlineEnabled());assertTrue(controls.overseasEnabled());
    }
    @Test void cancelledPreviewCannotExecute(){
        var p=cards.preview("u1",apply());assertEquals("CANCELLED",cards.cancel("u1",p.id()).status());
        assertThrows(BankException.class,()->cards.confirm("u1",p.id(),new CardConfirmDTO(p.payloadHash(),true)));
        assertTrue(cards.list("u1").isEmpty());
    }
}
