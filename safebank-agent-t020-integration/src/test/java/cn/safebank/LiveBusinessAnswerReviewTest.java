package cn.safebank;

import cn.safebank.model.DeepSeekModelClient;
import cn.safebank.model.ModelUsageMeter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in paid provider verification; synthetic facts, no bank client or write tools. */
@EnabledIfEnvironmentVariable(named="SAFEBANK_LIVE_REVIEW_TEST",matches="true")
class LiveBusinessAnswerReviewTest {
    record Example(String boundary,String question,String candidate,boolean shouldAccept) {}
    @Test void actualReviewerRejectsKnownBusinessErrorsAndAcceptsQualifiedExplanations() throws Exception {
        var json=new ObjectMapper();String key=System.getenv("BANK_MODEL_API_KEY");
        assertNotNull(key,"Local environment key required");assertFalse(key.isBlank());
        var client=new DeepSeekModelClient(json,"https://api.deepseek.com",key,"deepseek-v4-flash",Duration.ofSeconds(30));
        var meter=new ModelUsageMeter(json,client);meter.begin(6);
        var review=new BusinessAnswerReview(json,meter);
        var facts=new GroundedAnswer(json,List.of(
            new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",536933,"income_minor",1170000,
                "categories",Map.of("居住",250000,"维修",200000,"其他",86933),
                "transactions",List.of(Map.of("merchant","家电退款","amount_minor",320000,"category","退款","booked_on","2026-09-02")),
                "anomaly_candidates",List.of(Map.of("merchant","设备维修","amount_minor",-200000,"category","维修","booked_on","2026-09-10")))),
            new BankReplyComposer.Evidence("list_mandates",List.of(Map.of("merchant","旧服务","status","REVOKED","amount_minor",900,"billing_interval","MONTHLY")))));
        var examples=List.of(
            new Example("revocation","已撤销授权还会收费吗？","旧服务银行侧授权已撤销，不会再扣。",false),
            new Example("accounting","退款计入支出吗？","九月支出口径包含退款等非工资入账。",false),
            new Example("risk","这些大额支出风险高吗？","这些大额支出类别明确，风险特征并不突出。",false),
            new Example("revocation","已撤销授权还会收费吗？","旧服务银行侧授权已撤销，但不能保证未来不再收费，商户合同状态未知。",true),
            new Example("accounting","退款计入支出吗？","正向退款计入入账，不抵减本系统支出；九月支出合计5,369.33元。",true),
            new Example("risk","这些大额支出风险高吗？","大额候选只按金额筛选，无法据此判断风险高低，请核对交易用途和凭证。",true));
        var records=new ArrayList<Map<String,Object>>();
        for(var example:examples){
            boolean accepted=true,validReview=true;String feedback="";long started=System.nanoTime();
            try{review.check(List.of(Map.of("role","user","content",example.question())),facts,example.candidate());}
            catch(BankException rejected){accepted=false;feedback=rejected.code+": "+rejected.getMessage();
                validReview=rejected.code.equals("ANSWER_NOT_GROUNDED")&&rejected.getMessage().startsWith("业务语义需修正：");}
            records.add(Map.of("boundary",example.boundary(),"question",example.question(),"candidate",example.candidate(),
                "shouldAccept",example.shouldAccept(),"accepted",accepted,"feedback",feedback,"validReview",validReview,"passed",validReview&&accepted==example.shouldAccept(),
                "elapsedMs",Duration.ofNanos(System.nanoTime()-started).toMillis()));
            Files.createDirectories(Path.of("target/eval"));
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/eval/v041-live-review.json").toFile(),Map.of("scope","six read-only model reviews; synthetic evidence, no execution tools","model_usage",meter.snapshot(),"records",records));
            assertTrue(validReview,"Invalid/provider review must not count as detecting a business error; see report. Remaining requests stopped.");
        }
        assertEquals(0,records.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).count(),"See target/eval/v041-live-review.json; provider reviews are not a universal semantic guarantee");
    }
}
