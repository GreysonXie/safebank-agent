package cn.safebank;

import cn.safebank.dao.*;
import cn.safebank.entity.*;
import cn.safebank.dto.AgentToolRequestDTO;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T014 前置：新 scope 的授权链最小检查（复用真实 AgentCapabilityService.call/authorize 与参数入口）。
 * 只把持久化协作者换成受控依赖：内存 mapper、GatewayTurnService.lock/now、用户锁直接执行；
 * 被测门禁（token 形状、grant 存在/状态/有效期/scope 一致性、参数白名单、回执哈希）全部走真实代码。
 * 这是 H2/受控依赖层的证据，不替代 MySQL 并发或 HTTP 攻击测试。
 */
class AgentScopeGrantTest {
    private static final String TOKEN="a".repeat(64);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();

    private final Map<String,AgentServiceGrantEntity> grants=new LinkedHashMap<>();
    private final List<AgentToolCallReceiptEntity> receipts=new ArrayList<>();

    private TransactionMapper transactionMapper(){
        var rows=List.of(row("u1","t1","餐饮",-10001,"2026-09-03"),row("u2","t2","餐饮",-777777,"2026-09-04"));
        return new TransactionMapper(){
            public TransactionEntity selectActionDebit(String userId,String id){return null;}
            public List<TransactionEntity> selectPeriod(String userId,LocalDate start,LocalDate end){
                return rows.stream().filter(r->r.getUserId().equals(userId)&&!r.getBookedOn().isBefore(start)&&r.getBookedOn().isBefore(end)).toList();
            }
            public List<cn.safebank.dao.projection.RecurringPaymentRow> selectRecurring(String userId){return List.of();}
            public int insert(TransactionEntity row){return 1;}
        };
    }
    private TransactionEntity row(String user,String id,String category,long amount,String booked){
        var entity=new TransactionEntity();
        entity.setId(id);entity.setUserId(user);entity.setAccountId("checking");entity.setMerchant(id+"商户");
        entity.setCategory(category);entity.setAmountMinor(amount);entity.setBookedOn(LocalDate.parse(booked));
        return entity;
    }
    private AgentGatewayMapper mapper(){
        return new AgentGatewayMapper(){
            public AgentGatewayTurnEntity gateway(String turnId){return null;}
            public AgentGatewayTurnEntity lockGateway(String turnId){return null;}
            public int insertGateway(AgentGatewayTurnEntity row){return 1;}
            public int updateGateway(AgentGatewayTurnEntity row){return 1;}
            public AgentTurnReceiptEntity lockTurn(String userId,String turnId){return null;}
            public AgentServiceGrantEntity grant(String tokenHash){return grants.get(tokenHash);}
            public AgentServiceGrantEntity grantCurrent(String tokenHash){return grants.get(tokenHash);}
            public int revoke(String userId,String turnId){return 1;}
            public int insertGrant(AgentServiceGrantEntity row){grants.put(row.getTokenHash(),row);return 1;}
            public int useGrant(String id){return 1;}
            public AgentToolCallReceiptEntity receipt(String turnId,String key){return null;}
            public List<AgentToolCallReceiptEntity> receipts(String turnId){
                return receipts.stream().filter(r->turnId.equals(r.getTurnId())).toList();
            }
            public int insertReceipt(AgentToolCallReceiptEntity row){receipts.add(row);return 1;}
            public List<AgentGatewayTurnEntity> due(OffsetDateTime now){return List.of();}
            public List<String> pendingAck(String userId,String conversationId){return List.of();}
        };
    }
    private PlatformTransactionManager transactions(){
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return manager;
    }
    private AgentCapabilityService service(Set<String> scope){
        var grant=new AgentServiceGrantEntity();
        grant.setId(UUID.randomUUID().toString());grant.setTokenHash(cn.safebank.service.ContextEvidenceService.hash(TOKEN));grant.setUserId("u1");
        grant.setConversationId("c1");grant.setTurnId("t1");grant.setRequestId("r1");
        grant.setScopeJson(json.valueToTree(new ArrayList<>(new TreeSet<>(scope))).toString());
        grant.setState("ACTIVE");grant.setCallLimit(8);grant.setCallsUsed(0);
        grant.setCreatedAt(OffsetDateTime.now().minusMinutes(1));grant.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        grants.clear();grants.put(grant.getTokenHash(),grant);
        var turns=mock(GatewayTurnService.class);
        var turn=new AgentGatewayTurnEntity();
        turn.setTurnId("t1");turn.setUserId("u1");turn.setConversationId("c1");turn.setInputVersion(1L);
        when(turns.lock(anyString(),anyBoolean())).thenReturn(turn);
        when(turns.now()).thenReturn(OffsetDateTime.now());
        var statements=new StatementService(transactionMapper(),Clock.systemUTC());
        var service=new AgentCapabilityService(mapper(),null,turns,null,statements,json,transactions(),null);
        var locks=mock(RiskUserLockService.class);
        when(locks.withUserLock(anyString(),any())).thenAnswer(invocation->((java.util.function.Supplier<?>)invocation.getArgument(1)).get());
        set(service,"riskLocks",locks);
        set(service,"wealth",new cn.safebank.wealth.WealthCatalog(json));
        set(service,"wealthHistory",new cn.safebank.wealth.WealthHistoryCatalog(json,Clock.systemUTC()));
        return service;
    }
    private void set(Object target,String field,Object value){
        try{var handle=target.getClass().getDeclaredField(field);handle.setAccessible(true);handle.set(target,value);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private AgentToolRequestDTO signed(AgentCapabilityService service,String tool,Map<String,Object> args)throws Exception{
        var node=(com.fasterxml.jackson.databind.JsonNode)json.valueToTree(args);
        return new AgentToolRequestDTO(cn.safebank.service.ContextEvidenceService.hash(TOKEN),service.inputHash(tool,node),tool,node);
    }
    private Set<String> scopesWithFilter(){
        var set=new TreeSet<>(cn.safebank.service.AgentCapabilityService.SCOPES);
        assertTrue(set.contains("statement_filter"));
        return set;
    }

    @Test void validGrantUsesTrustedUserIdFromGrantForTheNewScope() throws Exception{
        var service=service(scopesWithFilter());
        var receipt=service.call(TOKEN,signed(service,"statement_filter",Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",10000)));
        assertEquals("statement_filter",receipt.tool());
        var facts=receipt.facts();
        assertEquals(new java.math.BigDecimal("1"),facts.get("statement_filter_2026_09_expense_min10000_餐饮.matched_count").number(),
            "只统计 grant 归属用户 u1 的记录（u2 的 777.77 元不进入）");
        assertEquals(new java.math.BigDecimal("10001"),facts.get("statement_filter_2026_09_expense_min10000_餐饮.expense_minor").number());
    }

    @Test void missingOrExpiredGrantIsDenied() throws Exception{
        var service=service(scopesWithFilter());
        grants.clear();
        assertThrows(BankException.class,()->service.call(TOKEN,signed(service,"statement_filter",Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",0))),"缺少 grant 被拒");
        service(scopesWithFilter());
        grants.values().forEach(grant->grant.setState("REVOKED"));
        assertThrows(BankException.class,()->service.call(TOKEN,signed(service,"statement_filter",Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",0))),"非 ACTIVE grant 被拒");
        service(scopesWithFilter());
        grants.values().forEach(grant->grant.setExpiresAt(OffsetDateTime.now().minusMinutes(1)));
        assertThrows(BankException.class,()->service.call(TOKEN,signed(service,"statement_filter",Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",0))),"过期 grant 被拒");
        assertThrows(BankException.class,()->service.call("not-a-token",signed(service,"statement_filter",Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",0))),"token 形状非法被拒");
    }

    @Test void wealthHistoryAcceptsNaturalMonthForm() throws Exception{
        var service=service(scopesWithFilter());
        for(String month:List.of("2026-09","9","上月","最近")){
            var receipt=service.call(TOKEN,signed(service,"wealth_history",Map.of("productId","DEMO-WM-03","month",month)));
            assertTrue(receipt.facts().keySet().stream().anyMatch(key->key.startsWith("wealth_history_DEMO-WM-03_")),month+" 应解析为区间事实");
        }
        freshCheck(service);
    }
    private void freshCheck(AgentCapabilityService service) throws Exception{
        var node=(com.fasterxml.jackson.databind.JsonNode)json.valueToTree(Map.of("productId","DEMO-WM-03","month","2026-09","from","2026-09-01"));
        var request=new AgentToolRequestDTO(cn.safebank.service.ContextEvidenceService.hash(TOKEN),service.inputHash("wealth_history",node),"wealth_history",node);
        assertEquals("VALIDATION_ERROR",assertThrows(BankException.class,()->service.call(TOKEN,request)).code,"month 与 from/to 不能混用");
    }
    @Test void wealthHistoryScopeUsesSameTrustedGrantPath() throws Exception{
        var service=service(scopesWithFilter());
        var receipt=service.call(TOKEN,signed(service,"wealth_history",Map.of("productId","DEMO-WM-02","from","2026-09-01","to","2026-09-30")));
        assertEquals("wealth_history",receipt.tool());
        assertTrue(receipt.facts().containsKey("wealth_history_DEMO-WM-02_2026_09_01_2026_09_30_v1.change_percent"),"事实身份含产品、实际区间与版本");
        var without=new TreeSet<>(cn.safebank.service.AgentCapabilityService.SCOPES);
        without.remove("wealth_history");
        var limited=service(without);
        assertEquals("PERMISSION_DENIED",assertThrows(BankException.class,()->limited.call(TOKEN,signed(limited,"wealth_history",Map.of("productId","DEMO-WM-02","from","2026-09-01","to","2026-09-30")))).code);
        var fresh=service(scopesWithFilter());
        var node=(com.fasterxml.jackson.databind.JsonNode)json.valueToTree(Map.of("productId","DEMO-WM-02","from","2026-09-01","to","2026-09-30","userId","u2"));
        var request=new AgentToolRequestDTO(cn.safebank.service.ContextEvidenceService.hash(TOKEN),fresh.inputHash("wealth_history",node),"wealth_history",node);
        assertEquals("VALIDATION_ERROR",assertThrows(BankException.class,()->fresh.call(TOKEN,request)).code,"额外 userId 不被接受");
    }
    @Test void grantWithoutTheNewScopeIsDenied() throws Exception{
        var without=new TreeSet<>(cn.safebank.service.AgentCapabilityService.SCOPES);
        without.remove("statement_filter");
        var service=service(without);
        var error=assertThrows(BankException.class,()->service.call(TOKEN,signed(service,"statement_filter",Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",0))));
        assertEquals("PERMISSION_DENIED",error.code,"scope 不一致时按既有门禁拒绝");
    }

    @Test void extraUserIdArgumentIsRejectedBeforeAnyQuery() throws Exception{
        var service=service(scopesWithFilter());
        var node=(com.fasterxml.jackson.databind.JsonNode)json.valueToTree(Map.of("month","2026-09","category","餐饮","direction","支出","minAmountMinor",0,"userId","u2"));
        var request=new AgentToolRequestDTO(cn.safebank.service.ContextEvidenceService.hash(TOKEN),service.inputHash("statement_filter",node),"statement_filter",node);
        var error=assertThrows(BankException.class,()->service.call(TOKEN,request));
        assertEquals("VALIDATION_ERROR",error.code,"额外 userId 不被接受");
    }
}
