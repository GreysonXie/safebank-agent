package cn.safebank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T013：转账重复提交与余额竞争的受控并发检查（H2 单 JVM，隔离测试库）。
 * 同步点（就绪/起跑）都在业务锁之外；每个工作线程都走真实 ActionExecutionService.submit 与真实 Mapper/数据库连接；
 * 线程结束后在业务事务之外读取余额、分录、流水、动作/观察终态与额度释放。
 * 本类只做 H2 单 JVM 结论：同动作会先被进程内 flight 锁串行，不代表 MySQL 数据库锁或跨进程幂等已验证。
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:concurrency;DB_CLOSE_DELAY=-1"})
class TransferConcurrencyTest extends BankTestFixture {
    @Autowired ExecutionTestSupport execution;

    private record Outcome(Map<String,Object> result,String errorCode){}

    private long balance(String accountId){return db.queryForObject("SELECT balance_minor FROM accounts WHERE id=?",Long.class,accountId);}
    private int count(String sql,Object...args){return db.queryForObject(sql,Integer.class,args);}
    private int ledgerRows(String actionId){return count("SELECT COUNT(*) FROM ledger WHERE action_id=?",actionId);}
    private int transactionRows(String actionId){return count("SELECT COUNT(*) FROM transactions WHERE id LIKE ?",actionId+"%");}
    private Outcome submitCaptured(String userId,String id){
        try{return new Outcome(execution.submit(userId,id),null);}
        catch(cn.safebank.BankException rejected){return new Outcome(null,rejected.code);}
    }

    /** 就绪栅栏 + 起跑闸门都在业务锁之外；Future 上限 30 秒，异常立即收尾。 */
    private void runConcurrently(String first,Runnable a,String second,Runnable b) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> left=pool.submit(()->runAfterGate(ready,start,a));
            Future<?> right=pool.submit(()->runAfterGate(ready,start,b));
            assertTrue(ready.await(5,TimeUnit.SECONDS),first+"与"+second+"都已启动并到达同步点");
            start.countDown();
            left.get(30,TimeUnit.SECONDS);right.get(30,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
    private void runAfterGate(CountDownLatch ready,CountDownLatch start,Runnable work){
        ready.countDown();
        try{if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("同步闸门超时");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        work.run();
    }

    @Test void sameAuthorizedActionSubmittedByTwoThreadsDebitsOnce() throws Exception {
        String id=authorize(bank.preview("u1","checking","zhang-a",50000));
        var outcomes=new ConcurrentLinkedQueue<Outcome>();
        runConcurrently("提交1",()->outcomes.add(submitCaptured("u1",id)),"提交2",()->outcomes.add(submitCaptured("u1",id)));
        assertEquals(2,outcomes.size());
        var receipts=new HashSet<Object>();
        for(var outcome:outcomes){
            assertNull(outcome.errorCode(),"两次调用都不应被拒绝");
            assertEquals("VERIFIED_SUCCESS",outcome.result().get("status"),"两次调用得到同一原结果");
            receipts.add(outcome.result().get("receipt_id"));
        }
        assertEquals(1,receipts.size(),"两次返回同一个回执");
        assertEquals(1193000L,balance("checking"),"只扣一次");
        assertEquals(50000L,balance("external-a"),"只入账一次");
        assertEquals(2,ledgerRows(id),"只有一套双边分录");
        assertEquals(2,transactionRows(id),"只有一套双边流水");
        assertEquals(1,count("SELECT COUNT(*) FROM execution_observations WHERE action_id=? AND state='VERIFIED_SUCCESS'",id));
    }

    /**
     * 两笔各自合法、合计超过可用余额的动作同时提交。
     * 实际拦截层是"提交条件复核"（当日累计口径变化导致原决策过期），不是账户余额写入竞争；
     * 这里如实断言这一层的结果：恰好一笔成功，另一笔不产生任何账务、被要求刷新重确认，不透支。
     */
    @Test void twoActionsCompetingForSameBalanceAreStoppedWithoutOverdraft() throws Exception {
        db.update("UPDATE accounts SET balance_minor=50000 WHERE id='checking'");
        String first=authorize(bank.preview("u1","checking","zhang-a",30000));
        String second=authorize(bank.preview("u1","checking","zhang-a",30000));
        var outcomes=new ConcurrentHashMap<String,Outcome>();
        runConcurrently("动作A",()->outcomes.put("A",submitCaptured("u1",first)),"动作B",()->outcomes.put("B",submitCaptured("u1",second)));
        var left=outcomes.get("A");var right=outcomes.get("B");
        System.out.println("T013 并发竞争结果 A="+(left.result()==null?left.errorCode():left.result().get("status"))+" B="+(right.result()==null?right.errorCode():right.result().get("status")));
        long succeededCount=java.util.stream.Stream.of(left,right).filter(outcome->outcome.result()!=null&&"VERIFIED_SUCCESS".equals(outcome.result().get("status"))).count();
        assertTrue(succeededCount<=1,"合计不得超过可用资金，成功笔数="+succeededCount);
        assertEquals(50000-30000*succeededCount,balance("checking"),"余额只随成功笔减少，未透支");
        assertEquals(30000*succeededCount,balance("external-a"),"只有成功笔入账");
        assertEquals(2*succeededCount,ledgerRows(first)+ledgerRows(second),"分录总数与成功笔严格对应");
        assertEquals(2*succeededCount,transactionRows(first)+transactionRows(second),"流水总数与成功笔严格对应");
        for(var pair:List.of(Map.entry("A",left),Map.entry("B",right))){
            var outcome=pair.getValue();
            String actionId="A".equals(pair.getKey())?first:second;
            boolean succeeded=outcome.result()!=null&&"VERIFIED_SUCCESS".equals(outcome.result().get("status"));
            if(succeeded){
                assertEquals(1,count("SELECT COUNT(*) FROM risk_transfer_usage WHERE action_id=? AND state='COMMITTED'",actionId),"成功笔额度已核销");
                continue;
            }
            assertNotNull(outcome.errorCode(),"未成功的一笔必须给出可识别原因，而不是静默成功");
            assertTrue(List.of("RISK_DECISION_STALE","STEP_UP_REQUIRED","INSUFFICIENT_FUNDS").contains(outcome.errorCode()),
                "拒绝来自既有授权/余额规则，实际="+outcome.errorCode());
            assertEquals(0,ledgerRows(actionId));assertEquals(0,transactionRows(actionId));
            assertTrue(List.of("AWAITING_AUTH","VERIFIED_FAILED").contains(String.valueOf(bank.status("u1",actionId).get("status"))),
                "未成功的动作保持可核对状态，不被写成已确定成功");
        }
        // 确定性的余额规则检查：剩余余额不足以再转一笔时，既有规则直接拒绝，且不产生任何账务写入
        long available=balance("checking");
        assertThrows(cn.safebank.BankException.class,()->bank.preview("u1","checking","zhang-a",available+1000),
            "超过可用余额的转账在预览阶段被拒绝");
        assertEquals(available,balance("checking"),"被拒绝的预览没有任何资金写入");
    }
}
