package cn.safebank;
import cn.safebank.model.*;
import static cn.safebank.model.ModelContracts.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:reply;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class AgentReplyTest extends BankTestFixture {
    @Autowired ObjectMapper json;
    AgentService scripted(String tool,Map<String,Object> args,String finalText) {
        ModelClient client=new ModelClient() {
            int round;
            public String mode() { return "SCRIPTED_TEST"; }
            public String modelName() { return "scripted"; }
            public ModelReply complete(ModelRequest request) {
                var message=round++==0?json.valueToTree(Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-1","type","function","function",Map.of("name",tool,"arguments",encode(args)))))):json.valueToTree(Map.of("role","assistant","content",finalText));
                return new ModelReply(message,json.createObjectNode());
            }
        };
        return new AgentService(gateway,json,client);
    }
    String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    @Test void statementReplyShowsActualTotalsAndLargestCategoryInsteadOfGenericNotice() {
        var answer=scripted("get_statement",Map.of("month","2026-09"),"总支出只有1元，已自动退款").chat("u1","分析9月账单",new MockHttpSession());
        String text=answer.get("text").toString();
        assertTrue(text.contains("3,403.00"),text);assertTrue(text.contains("8,500.00"),text);assertTrue(text.contains("居住"),text);assertTrue(text.contains("2,500.00"),text);
        assertFalse(text.contains("已自动退款"));assertEquals(1243000,bank.balance("u1","checking"));assertTrue(((List<?>)answer.get("actions")).isEmpty());
    }
    static java.util.stream.Stream<Arguments> readableQueries() {
        return java.util.stream.Stream.of(
            Arguments.of("get_accounts",Map.of(),"12,430.00","旅游账户"),
            Arguments.of("list_mandates",Map.of(),"请确认","代扣列表"),
            Arguments.of("detect_recurring_payments",Map.of(),"音乐服务","使用频率"),
            Arguments.of("resolve_recipient",Map.of("query","张三"),"同学","同事"),
            Arguments.of("list_transfer_schedules",Map.of(),"没有已登记","定时"));
    }
    @ParameterizedTest @MethodSource("readableQueries")
    void readRepliesExplainBankDataWithoutTrustingUnverifiedModelClaims(String tool,Map<String,Object> args,String expected,String another) {
        String text=scripted(tool,args,"转账成功并到账，已经取消商户合同").chat("u1","查询",new MockHttpSession()).get("text").toString();
        assertTrue(text.contains(expected),text);assertTrue(text.contains(another),text);assertFalse(text.contains("转账成功并到账"));assertFalse(text.contains("已经取消商户合同"));
    }
    @Test void transferPreviewReplyNamesAmountAndRecipientButCannotClaimExecuted() {
        var answer=scripted("preview_transfer",Map.of("source_id","checking","recipient_id","zhang-a","amount_minor",50000),"已转账成功").chat("u1","只预览转账",new MockHttpSession());
        String text=answer.get("text").toString();assertTrue(text.contains("500.00"),text);assertTrue(text.contains("张三"),text);assertTrue(text.contains("尚未执行"),text);
        assertFalse(text.contains("已转账成功"));assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void pendingReceiptIsExplicitAndDoesNotClaimCompleted() {
        var action=bank.preview("u1","checking","zhang-a",50000);bank.authorize("u1",action.get("id").toString(),action.get("payload_hash").toString());bank.acceptPending("u1",action.get("id").toString());
        String text=scripted("get_action_status",Map.of("action_id",action.get("id")),"已经到账").chat("u1","查单",new MockHttpSession()).get("text").toString();
        assertTrue(text.contains("处理中"),text);assertFalse(text.contains("已经到账"));assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void emptyMonthExplainsNoRecordsWithoutInferringZeroAccountBalance() {
        String text=scripted("get_statement",Map.of("month","2026-03"),"账户余额为零").chat("u1","查3月账单",new MockHttpSession()).get("text").toString();
        assertTrue(text.contains("没有查询到交易记录"),text);assertTrue(text.contains("不代表账户余额为零"),text);assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void twoToolResultsBothRemainInReplyAndEvidence() {
        ModelClient client=new ModelClient() {
            int round;
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request) {
                var calls=List.of(Map.of("id","statement","type","function","function",Map.of("name","get_statement","arguments","{\"month\":\"2026-09\"}")),Map.of("id","accounts","type","function","function",Map.of("name","get_accounts","arguments","{}")));
                return new ModelReply(round++==0?json.valueToTree(Map.of("role","assistant","tool_calls",calls)):json.valueToTree(Map.of("role","assistant","content","已转账成功")),json.createObjectNode());
            }
        };
        var answer=new AgentService(gateway,json,client).chat("u1","查询账单和余额",new MockHttpSession());String text=answer.get("text").toString();
        assertTrue(text.contains("3,403.00"),text);assertTrue(text.contains("12,430.00"),text);assertEquals(2,((List<?>)answer.get("results")).size());assertFalse(text.contains("已转账成功"));
    }
    @Test void greetingIsUsefulWithoutInventingBankResults() {
        ModelClient client=new ModelClient(){public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}public ModelReply complete(ModelRequest request){return new ModelReply(json.valueToTree(Map.of("role","assistant","content","账户有999万元，转账成功")),json.createObjectNode());}};
        String text=new AgentService(gateway,json,client).chat("u1","你好",new MockHttpSession()).get("text").toString();
        assertTrue(text.contains("你好"),text);assertTrue(text.contains("账单"),text);assertFalse(text.contains("999"));assertFalse(text.contains("转账成功"));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"QUARTERLY","UNKNOWN"})
    void nonMonthlyRevocationNeverInventsMonthlySaving(String cycle) {
        db.update("UPDATE mandates SET billing_interval=? WHERE id='music'",cycle);
        var answer=scripted("preview_revoke",Map.of("mandate_id","music"),"每月已节省15元").chat("u1","撤销该项代扣",new MockHttpSession());
        String text=answer.get("text").toString();assertTrue(text.contains("不估算月度节省"),text);assertFalse(text.contains("预计月度支出减少"),text);
        var action=(Map<?,?>)((List<?>)answer.get("actions")).getFirst();assertEquals(cycle,action.get("billing_interval"));assertFalse(action.containsKey("estimated_monthly_saving_minor"));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
}
