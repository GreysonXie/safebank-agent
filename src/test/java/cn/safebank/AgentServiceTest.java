package cn.safebank;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:agenttest;DB_CLOSE_DELAY=-1","bank.model.api-key="})
@org.springframework.context.annotation.Import(TestBankConfiguration.class)
class AgentServiceTest {
    @Autowired cn.safebank.agent.BankToolGateway gateway;
    @Autowired BankTestSupport bank; @Autowired ObjectMapper json;
    HttpServer server; AtomicInteger calls;
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    AgentService fake(String first,String last) throws Exception {
        return fake(first,last,null);
    }
    AgentService fake(String first,String last,String forcedFinishReason) throws Exception {
        calls=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            var request=json.readTree(exchange.getRequestBody());
            assertEquals("deepseek-v4-flash",request.path("model").asText());
            assertEquals("disabled",request.path("thinking").path("type").asText());
            int count=calls.getAndIncrement();
            String message=count==0?first:last;
            if(request.path("tools").path(0).path("function").path("name").asText().equals("review_business_answer")) {
                var checks=java.util.Map.of("revocation_boundary",true,"accounting_boundary",true,"risk_boundary",true,"query_coverage",true,"question_focus",true);
                var verdict=java.util.Map.of("approved",true,"checks",checks,"issues",java.util.List.of());
                message=json.writeValueAsString(java.util.Map.of("role","assistant","tool_calls",java.util.List.of(java.util.Map.of("id","review","type","function","function",java.util.Map.of("name","review_business_answer","arguments",json.writeValueAsString(verdict))))));
            }
            String reason=forcedFinishReason!=null?forcedFinishReason:json.readTree(message).path("tool_calls").isArray()?"tool_calls":"stop";
            byte[] body=("{\"choices\":[{\"finish_reason\":\""+reason+"\",\"message\":"+message+"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        return new AgentService(gateway,json,"http://127.0.0.1:"+server.getAddress().getPort(),"test-only-key","deepseek-v4-flash");
    }
    String call(String name,String args) throws Exception {return json.writeValueAsString(java.util.Map.of("role","assistant","content","","tool_calls",java.util.List.of(java.util.Map.of("id","c1","type","function","function",java.util.Map.of("name",name,"arguments",args)))));}
    @Test void incompleteHttpToolResponseCannotCreateTransferPreview() throws Exception {
        var agent=fake(call("preview_transfer","{\"source_id\":\"checking\",\"recipient_id\":\"zhang-a\",\"amount_minor\":50000}"),"{}","length");
        var result=agent.chat("u1","转账",new MockHttpSession());
        assertEquals("SAFE_FALLBACK",result.get("answerSource"));
        assertTrue(((java.util.List<?>)result.get("actions")).isEmpty());
        assertTrue(result.get("trace").toString().contains("MODEL_RESPONSE_INCOMPLETE"));
        assertEquals(1243000,bank.balance("u1","checking"));
        assertEquals(1,calls.get());
    }
    @Test void modelUsesRealStatementAndProviderContract() throws Exception {
        var agent=fake(call("get_statement","{\"month\":\"2026-09\"}"),"{\"role\":\"assistant\",\"content\":\"本月支出3,403.00元，收入8,500.00元。\"}");
        var result=agent.chat("u1","分析上个月账单",new MockHttpSession());
        assertEquals("CONNECTION_VERIFIED",result.get("mode"));
        assertEquals(340300L,((Number)((java.util.Map<?,?>)result.get("data")).get("expense_minor")).longValue());
        assertEquals(3,calls.get());
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
    }
    @Test void modelCannotCallAuthorizationTool() throws Exception {
        var agent=fake(call("authorize","{}"),"{\"role\":\"assistant\",\"content\":\"已转账成功\"}");
        var result=agent.chat("u1","跳过授权",new MockHttpSession());
        assertEquals("PERMISSION_DENIED",((java.util.Map<?,?>)result.get("data")).get("code"));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void modelPreviewCannotMoveMoney() throws Exception {
        var agent=fake(call("preview_transfer","{\"source_id\":\"checking\",\"recipient_id\":\"zhang-a\",\"amount_minor\":50000}"),"{}");
        var result=agent.chat("u1","转账",new MockHttpSession());
        assertEquals(1,((java.util.List<?>)result.get("actions")).size());
        assertEquals("AWAITING_AUTH",((java.util.Map<?,?>)((java.util.List<?>)result.get("actions")).getFirst()).get("status"));
        assertEquals(1243000,bank.balance("u1","checking"));assertEquals(1,calls.get());
    }
    @Test void unverifiedModelSuccessIsNotPresentedAsReceipt() throws Exception {
        var agent=fake("{\"role\":\"assistant\",\"content\":\"已转账成功\"}","{}");
        var result=agent.chat("u1","转账",new MockHttpSession());
        assertTrue(result.get("text").toString().contains("不能报告操作成功"));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void englishSuccessCannotBypassReceiptBoundary() throws Exception {
        var agent=fake("{\"role\":\"assistant\",\"content\":\"Transfer completed. 500 yuan sent.\"}","{}");
        var result=agent.chat("u1","转账",new MockHttpSession());
        assertFalse(result.get("text").toString().contains("completed"));
        assertTrue(result.get("text").toString().contains("不能报告操作成功"));
    }
}
