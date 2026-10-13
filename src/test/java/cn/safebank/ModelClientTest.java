package cn.safebank;
import cn.safebank.model.*;
import static cn.safebank.model.ModelContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ModelClientTest {
    @Test void onlyKnownNormalTerminationCanCountAsCompleted() {
        for(var reason:FinishReason.values()) {
            var provider=reason==FinishReason.UNKNOWN?"private-unrecognized":reason.name().toLowerCase(Locale.ROOT);
            assertEquals(reason,FinishReason.fromProvider(new ObjectMapper().valueToTree(provider)));
            assertEquals(reason==FinishReason.STOP||reason==FinishReason.TOOL_CALLS,
                new ModelReply(new ObjectMapper().createObjectNode(),null,reason).completed());
        }
        assertEquals(FinishReason.UNKNOWN,FinishReason.fromProvider(null));
        assertEquals(FinishReason.UNKNOWN,FinishReason.fromProvider(new ObjectMapper().missingNode()));
        assertEquals(FinishReason.UNKNOWN,FinishReason.fromProvider(new ObjectMapper().valueToTree(42)));
    }
    @Test void terminationIsPreservedAndUnknownValuesAreSanitizedWithUsage() throws Exception {
        var reason=new java.util.concurrent.atomic.AtomicReference<>("length");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{
            var b=("{\"choices\":[{\"finish_reason\":\""+reason.get()+"\",\"message\":{\"content\":\"partial\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}").getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();
        });server.start();
        var meter=new ModelUsageMeter(new ObjectMapper(),new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-key","scripted",Duration.ofSeconds(2)));
        meter.begin(2);
        var reply=meter.complete(request());
        assertEquals(FinishReason.LENGTH,reply.finishReason());assertFalse(reply.completed());
        reason.set("PRIVATE_UNKNOWN_REASON");
        assertEquals(FinishReason.UNKNOWN,meter.complete(request()).finishReason());
        var usage=new ObjectMapper().valueToTree(meter.snapshot());
        assertEquals(14,usage.path("total_tokens").asInt());
        assertEquals("LENGTH",usage.path("calls").get(0).path("finish_reason").asText());
        assertFalse(usage.toString().contains("PRIVATE_UNKNOWN_REASON"));
        meter.clear();
    }
    HttpServer server;
    @AfterEach void cleanup() { if(server!=null)server.stop(0); }
    ModelRequest request() { return new ModelRequest(List.of(Map.of("role","user","content","查余额")),List.of()); }
    @Test void missingKeyIsExplicitOffline() {
        var c=new DeepSeekModelClient(new ObjectMapper(),"https://api.deepseek.com","","deepseek-v4-flash",Duration.ofSeconds(1));assertEquals("OFFLINE_DEMO",c.mode());
    }
    @Test void unauthorizedResponseIsSanitizedAndNeverVerified() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{var b="secret-server-body".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(401,b.length);e.getResponseBody().write(b);e.close();});server.start();
        var c=new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"private-test-key","deepseek-v4-flash",Duration.ofSeconds(1));
        var error=assertThrows(BankException.class,()->c.complete(request()));assertEquals("MODEL_API_ERROR",error.code);assertFalse(error.getMessage().contains("secret"));assertFalse(error.getMessage().contains("private-test-key"));assertEquals("MODEL_CONFIGURED",c.mode());
    }
    @Test void timeoutIsUnavailableAndNeverVerified() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{try{Thread.sleep(400);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}e.close();});server.start();
        var c=new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-key","deepseek-v4-flash",Duration.ofMillis(100));
        assertEquals("MODEL_UNAVAILABLE",assertThrows(BankException.class,()->c.complete(request())).code);assertEquals("MODEL_CONFIGURED",c.mode());
    }
    @Test void configuredIsNotTheSameAsVerified() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{var b="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":7}}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        var c=new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-key","deepseek-v4-flash",Duration.ofSeconds(2));
        assertEquals("MODEL_CONFIGURED",c.mode());
        var reply=c.complete(request());
        assertEquals(7,reply.usage().path("total_tokens").asInt());
        assertEquals(FinishReason.UNKNOWN,reply.finishReason());
        assertFalse(reply.completed(),"Missing provider status is not evidence of completed output");
        assertEquals("CONNECTION_VERIFIED",c.mode());
    }
    @Test void invalidResponseRemovesVerifiedStatus() throws Exception {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{var b=(count.getAndIncrement()==0?"{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}":"not-json").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        var c=new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-key","deepseek-v4-flash",Duration.ofSeconds(2));
        c.complete(request());
        assertEquals("MODEL_RESPONSE_INVALID",assertThrows(BankException.class,()->c.complete(request())).code);
        assertEquals("MODEL_CONFIGURED",c.mode());
    }
}
