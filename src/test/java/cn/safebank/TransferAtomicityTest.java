package cn.safebank;

import cn.safebank.dao.*;
import cn.safebank.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T012：一次已授权转账的本地账务原子性。
 * 用真实授权/受理夹具进入实际执行入口；故障只在一个指定的关键写入点注入一次，其余 Mapper 真实执行。
 * 之后从业务事务之外读取余额、分录、流水、动作状态与观察记录，验证"要么一起成功、要么整体不生效"。
 * 故障包装只在测试上下文（@TestConfiguration），不进入运行版本。
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:atomicity;DB_CLOSE_DELAY=-1"})
@Import(TransferAtomicityTest.FaultConfiguration.class)
class TransferAtomicityTest extends BankTestFixture {
    @Autowired ExecutionTestSupport execution;
    @Autowired TestFaults faults;

    private long balance(String accountId){return db.queryForObject("SELECT balance_minor FROM accounts WHERE id=?",Long.class,accountId);}
    private int count(String sql,Object...args){return db.queryForObject(sql,Integer.class,args);}
    private int ledgerRows(String actionId){return count("SELECT COUNT(*) FROM ledger WHERE action_id=?",actionId);}
    private int transactionRows(String actionId){return count("SELECT COUNT(*) FROM transactions WHERE id LIKE ?",actionId+"%");}
    private String prepared(String target){return authorize(bank.preview("u1","checking",target,50000));}

    @BeforeEach void resetFaults(){faults.reset();}

    @Test void crossUserTransferCommitsBothSides(){
        String id=prepared("zhang-a");
        assertEquals(1243000L,balance("checking"));
        assertEquals(0L,balance("external-a"));
        assertEquals("VERIFIED_SUCCESS",execution.submit("u1",id).get("status"));
        assertEquals(1193000L,balance("checking"));
        assertEquals(50000L,balance("external-a"));
        assertEquals(2,ledgerRows(id));
        assertEquals(2,transactionRows(id));
        assertEquals(1,count("SELECT COUNT(*) FROM transactions WHERE id=? AND user_id='u1' AND account_id='checking' AND category='转账'",id+":debit"));
        assertEquals(1,count("SELECT COUNT(*) FROM transactions WHERE id=? AND account_id='external-a'",id+":credit"));
        assertEquals(1,count("SELECT COUNT(*) FROM execution_observations WHERE action_id=? AND state='VERIFIED_SUCCESS'",id));
    }

    @Test void ownAccountTransferKeepsBothSidesOwnedAndClassifiedAsInternal(){
        String id=prepared("travel-recipient");
        assertEquals("VERIFIED_SUCCESS",execution.submit("u1",id).get("status"));
        assertEquals(1193000L,balance("checking"));
        assertEquals(5870000L,balance("travel"));
        assertEquals(2,transactionRows(id));
        assertEquals(2,count("SELECT COUNT(*) FROM transactions WHERE id LIKE ? AND user_id='u1' AND category='内部转账'",id+"%"));
    }

    @Test void creditFailureRollsBackAlreadyExecutedDebit(){
        String id=prepared("zhang-a");
        faults.failAt="credit";
        faults.beforeFail=()->{
            assertEquals(1193000L,balance("checking"),"同一事务内借方已执行");
            assertEquals(0L,balance("external-a"),"贷方尚未执行");
        };
        var failure=assertThrows(IllegalStateException.class,()->execution.submit("u1",id));
        assertEquals("INJECTED_CREDIT_FAILURE",failure.getMessage());
        assertEquals(1243000L,balance("checking"),"借方随事务回滚");
        assertEquals(0L,balance("external-a"));
        assertEquals(0,ledgerRows(id));
        assertEquals(0,transactionRows(id));
        assertEquals("AUTHORIZED",bank.status("u1",id).get("status"),"没有写入矛盾的成功状态");
        assertEquals(1,count("SELECT COUNT(*) FROM execution_observations WHERE action_id=? AND state='UNKNOWN'",id),"受理前的独立提交仍保留可核对身份");
    }

    @Test void secondLedgerFailureRollsBackBothBalancesAndFirstEntry(){
        String id=prepared("zhang-a");
        faults.failAt="ledger2";
        faults.beforeFail=()->{
            assertEquals(1,ledgerRows(id),"第一条分录已写入且在同一事务内可见");
            assertEquals(1193000L,balance("checking"));
            assertEquals(50000L,balance("external-a"));
        };
        assertThrows(IllegalStateException.class,()->execution.submit("u1",id));
        assertEquals(1243000L,balance("checking"));
        assertEquals(0L,balance("external-a"));
        assertEquals(0,ledgerRows(id));
        assertEquals(0,transactionRows(id));
        assertEquals("AUTHORIZED",bank.status("u1",id).get("status"));
    }

    @Test void secondTransactionRowFailureRollsBackLedgerAndBalances(){
        String id=prepared("zhang-a");
        faults.failAt="transaction2";
        faults.beforeFail=()->{
            assertEquals(2,ledgerRows(id),"两条分录已写入");
            assertEquals(1,transactionRows(id),"第一条流水已写入");
        };
        assertThrows(IllegalStateException.class,()->execution.submit("u1",id));
        assertEquals(1243000L,balance("checking"));
        assertEquals(0L,balance("external-a"));
        assertEquals(0,ledgerRows(id));
        assertEquals(0,transactionRows(id));
        assertEquals("AUTHORIZED",bank.status("u1",id).get("status"));
    }

    @Test void successStateWriteFailureRollsBackFundsAndLedger(){
        String id=prepared("zhang-a");
        faults.failAt="successState";
        faults.beforeFail=()->{
            assertEquals(2,ledgerRows(id),"成功状态之前资金与分录已写入同一事务");
            assertEquals(2,transactionRows(id));
        };
        assertThrows(IllegalStateException.class,()->execution.submit("u1",id));
        assertEquals(1243000L,balance("checking"));
        assertEquals(0L,balance("external-a"));
        assertEquals(0,ledgerRows(id));
        assertEquals(0,transactionRows(id));
        assertEquals("AUTHORIZED",bank.status("u1",id).get("status"));
        assertEquals(0,count("SELECT COUNT(*) FROM execution_observations WHERE action_id=? AND state='VERIFIED_SUCCESS'",id));
    }

    @Test void afterRollbackOriginalRequestReconcilesAndCanBeConfirmedOnceMore(){
        Map<String,Object> preview=bank.preview("u1","checking","zhang-a",50000);
        String id=authorize(preview);
        faults.failAt="credit";
        assertThrows(IllegalStateException.class,()->execution.submit("u1",id));
        faults.reset();
        var reconciled=execution.submit("u1",id);
        assertEquals("NOT_SUBMITTED",reconciled.get("observation"),"核对后确认未提交（不是已确定失败）；实际返回="+reconciled);
        assertEquals("AWAITING_AUTH",bank.status("u1",id).get("status"));
        assertEquals(1243000L,balance("checking"));
        assertEquals(0,ledgerRows(id));
        assertEquals(0,count("SELECT COUNT(*) FROM execution_observations WHERE action_id=?",id),"受理前观察在确认未提交后清除");
        assertEquals(1,count("SELECT COUNT(*) FROM risk_transfer_usage WHERE action_id=? AND state='RELEASED'",id),"额度已释放");
        bank.authorize("u1",id,preview.get("payload_hash").toString());
        assertEquals("VERIFIED_SUCCESS",execution.submit("u1",id).get("status"),"重新确认后可完成一笔");
        assertEquals(1193000L,balance("checking"));
        assertEquals(2,ledgerRows(id));
    }

    /** 测试专用故障点：只在指定位置抛一次异常，其余写入真实执行。 */
    static class TestFaults {
        volatile String failAt;
        volatile Runnable beforeFail=()->{};
        final AtomicInteger ledgerCalls=new AtomicInteger();
        final AtomicInteger transactionCalls=new AtomicInteger();
        void reset(){failAt=null;beforeFail=()->{};ledgerCalls.set(0);transactionCalls.set(0);}
        void trip(String point,String message){
            if(!point.equals(failAt))return;
            failAt=null;
            beforeFail.run();
            throw new IllegalStateException(message);
        }
    }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean TestFaults testFaults(){return new TestFaults();}
        @Bean @Primary AccountMapper faultingAccounts(@Qualifier("accountMapper") AccountMapper delegate,TestFaults faults){
            return new FaultingAccounts(delegate,faults);
        }
        @Bean @Primary LedgerEntryMapper faultingLedger(@Qualifier("ledgerEntryMapper") LedgerEntryMapper delegate,TestFaults faults){
            return new FaultingLedger(delegate,faults);
        }
        @Bean @Primary TransactionMapper faultingTransactions(@Qualifier("transactionMapper") TransactionMapper delegate,TestFaults faults){
            return new FaultingTransactions(delegate,faults);
        }
        @Bean @Primary ActionMapper faultingActions(@Qualifier("actionMapper") ActionMapper delegate,TestFaults faults){
            return new FaultingActions(delegate,faults);
        }
    }

    static class FaultingAccounts implements AccountMapper {
        private final AccountMapper delegate;private final TestFaults faults;
        FaultingAccounts(AccountMapper delegate,TestFaults faults){this.delegate=delegate;this.faults=faults;}
        public List<AccountEntity> selectByUser(String userId){return delegate.selectByUser(userId);}
        public AccountEntity selectOwned(String userId,String accountId){return delegate.selectOwned(userId,accountId);}
        public AccountEntity selectById(String accountId){return delegate.selectById(accountId);}
        public AccountEntity lockById(String accountId){return delegate.lockById(accountId);}
        public int debit(String accountId,long amountMinor){return delegate.debit(accountId,amountMinor);}
        public int credit(String accountId,long amountMinor){faults.trip("credit","INJECTED_CREDIT_FAILURE");return delegate.credit(accountId,amountMinor);}
    }

    static class FaultingLedger implements LedgerEntryMapper {
        private final LedgerEntryMapper delegate;private final TestFaults faults;
        FaultingLedger(LedgerEntryMapper delegate,TestFaults faults){this.delegate=delegate;this.faults=faults;}
        public int insert(LedgerEntryEntity row){
            int calls=faults.ledgerCalls.incrementAndGet();
            if(calls==2)faults.trip("ledger2","INJECTED_SECOND_LEDGER_FAILURE");
            return delegate.insert(row);
        }
        public List<LedgerEntryEntity> selectByAction(String actionId){return delegate.selectByAction(actionId);}
    }

    static class FaultingTransactions implements TransactionMapper {
        private final TransactionMapper delegate;private final TestFaults faults;
        FaultingTransactions(TransactionMapper delegate,TestFaults faults){this.delegate=delegate;this.faults=faults;}
        public TransactionEntity selectActionDebit(String userId,String id){return delegate.selectActionDebit(userId,id);}
        public List<TransactionEntity> selectPeriod(String userId,java.time.LocalDate start,java.time.LocalDate end){return delegate.selectPeriod(userId,start,end);}
        public List<cn.safebank.dao.projection.RecurringPaymentRow> selectRecurring(String userId){return delegate.selectRecurring(userId);}
        public int insert(TransactionEntity row){
            int calls=faults.transactionCalls.incrementAndGet();
            if(calls==2)faults.trip("transaction2","INJECTED_SECOND_TRANSACTION_FAILURE");
            return delegate.insert(row);
        }
    }

    static class FaultingActions implements ActionMapper {
        private final ActionMapper delegate;private final TestFaults faults;
        FaultingActions(ActionMapper delegate,TestFaults faults){this.delegate=delegate;this.faults=faults;}
        public List<ActionEntity> selectByUser(String userId){return delegate.selectByUser(userId);}
        public int updateExpiry(String userId,String id,java.time.OffsetDateTime expiresAt){return delegate.updateExpiry(userId,id,expiresAt);}
        public ActionEntity selectOwned(String userId,String id){return delegate.selectOwned(userId,id);}
        public ActionEntity selectOwnedForUpdate(String userId,String id){return delegate.selectOwnedForUpdate(userId,id);}
        public int insert(ActionEntity row){return delegate.insert(row);}
        public int updateState(String userId,String id,String status,String receiptId,String errorCode){
            if("VERIFIED_SUCCESS".equals(status))faults.trip("successState","INJECTED_SUCCESS_STATE_FAILURE");
            return delegate.updateState(userId,id,status,receiptId,errorCode);
        }
    }
}
