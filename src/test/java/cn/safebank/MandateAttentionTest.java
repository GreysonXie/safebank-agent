package cn.safebank;

import cn.safebank.dao.MandateMapper;
import cn.safebank.entity.MandateEntity;
import cn.safebank.service.MandateService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** T017：以授权状态为主、日期关注程度为辅的固定分组（临近阈值 7 天，边界含 D+0/D+7/D+8）。 */
class MandateAttentionTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"),ZoneOffset.UTC);
    private MandateEntity row(String id,String merchant,long amount,String renewal,String status,String cycle){
        var entity=new MandateEntity();
        entity.setId(id);entity.setUserId("u1");entity.setMerchant(merchant);entity.setAmountMinor(amount);
        entity.setRenewalDate(renewal==null?null:LocalDate.parse(renewal));entity.setStatus(status);
        entity.setContractStatus("UNKNOWN");entity.setBillingInterval(cycle);
        return entity;
    }
    private MandateService service(List<MandateEntity> rows){
        return new MandateService(new MandateMapper(){
            public List<MandateEntity> selectByUser(String userId){return rows.stream().filter(r->r.getUserId().equals(userId)).toList();}
            public MandateEntity selectOwned(String userId,String id){return rows.stream().filter(r->r.getUserId().equals(userId)&&r.getId().equals(id)).findFirst().orElse(null);}
            public MandateEntity lockOwned(String userId,String id){return selectOwned(userId,id);}
            public int revoke(String userId,String id){return 1;}
        },clock);
    }

    @Test void attentionCoversStatusFirstThenDateBuckets(){
        var service=service(List.of(
            row("today","当天",1000,"2026-10-30","ACTIVE","MONTHLY"),
            row("near7","第七天",1000,"2026-11-06","ACTIVE","MONTHLY"),
            row("far8","第八天",1000,"2026-11-07","ACTIVE","MONTHLY"),
            row("past","已过",1000,"2026-10-29","ACTIVE","MONTHLY"),
            row("noDate","无日期",1000,null,"ACTIVE","UNKNOWN"),
            row("revoked","已撤销",1000,"2026-10-29","REVOKED","MONTHLY"),
            row("unknown","状态未知",1000,"2026-10-29","PENDING","MONTHLY")));
        var byId=new HashMap<String,String>();
        for(var mandate:service.list("u1"))byId.put(mandate.id(),mandate.attention());
        assertEquals("临近（7天内）",byId.get("today"));
        assertEquals("临近（7天内）",byId.get("near7"),"第 7 天仍算临近");
        assertEquals("远期",byId.get("far8"),"第 8 天进入远期");
        assertEquals("记录日期已过待核对",byId.get("past"),"旧日期不是授权失效");
        assertEquals("日期未知",byId.get("noDate"));
        assertEquals("已撤销",byId.get("revoked"),"已撤销不进入有效分组");
        assertEquals("状态未确认",byId.get("unknown"));
    }

    @Test void daysToRenewalIsRelativeToBusinessDateAndOnlyForKnownDates(){
        var list=service(List.of(
            row("near7","第七天",1000,"2026-11-06","ACTIVE","MONTHLY"),
            row("past","已过",1000,"2026-10-29","ACTIVE","MONTHLY"),
            row("noDate","无日期",1000,null,"ACTIVE","UNKNOWN"))).list("u1");
        var byId=new HashMap<String,Integer>();
        for(var mandate:list)byId.put(mandate.id(),mandate.daysToRenewal());
        assertEquals(7,byId.get("near7"));
        assertEquals(-1,byId.get("past"));
        assertNull(byId.get("noDate"));
    }

    @Test void quarterlyAndUnknownCyclesDoNotCountAsKnownMonthlyTotal(){
        var service=service(List.of(
            row("monthly","月付",1500,"2026-11-02","ACTIVE","MONTHLY"),
            row("quarterly","季付",18000,"2026-12-09","ACTIVE","QUARTERLY"),
            row("unknown","未知",1200,null,"ACTIVE","UNKNOWN"),
            row("revoked","已撤销",900,"2026-10-29","REVOKED","MONTHLY")));
        var answer=new GroundedAnswer(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
            List.of(new BankReplyComposer.Evidence("list_mandates",service.list("u1"))));
        var facts=answer.factCatalog();
        assertEquals(new java.math.BigDecimal("1500"),facts.get("mandates_summary.known_monthly_total_minor").number(),"只有明确月付且有效计入");
        assertEquals(new java.math.BigDecimal("3"),facts.get("mandates_summary.active_count").number(),"有效项计数包含月付/季付/未知周期，不含已撤销");
        assertEquals(new java.math.BigDecimal("1"),facts.get("mandates_summary.revoked_count").number());
        assertTrue(facts.keySet().stream().anyMatch(key->key.endsWith(".attention")),"事实目录包含关注分组");
    }
}
