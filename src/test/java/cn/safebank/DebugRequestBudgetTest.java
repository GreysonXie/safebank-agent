package cn.safebank;
import cn.safebank.model.*;
import cn.safebank.model.ModelContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class DebugRequestBudgetTest {
    @TempDir Path temp;
    @Test void legacyLedgerMigratesWithoutLosingReservations() throws Exception {
        var path=temp.resolve("resize.json");var original=new DebugRequestBudget(path,20);
        String first=original.reserveEffect("1".repeat(64),"2".repeat(64),"original","plan");
        for(int i=1;i<20;i++)original.reserve();
        var larger=new DebugRequestBudget(path,100);
        assertEquals(Integer.MAX_VALUE,larger.remaining());assertEquals(20,larger.snapshot().reserved());
        assertEquals(first,larger.reserveEffect("1".repeat(64),"2".repeat(64),"original","plan"));
        larger.reserve();assertEquals(21,new DebugRequestBudget(path,100).snapshot().reserved());
        assertTrue(new ObjectMapper().readTree(Files.readString(path)).path("unlimited").asBoolean());
    }
    @Test void historicalLimitsNeverBlockNewSends() {
        var path=temp.resolve("lower.json");var original=new DebugRequestBudget(path,20);
        String first=original.reserveEffect("1".repeat(64),"2".repeat(64),"original","plan");
        for(int i=1;i<10;i++)original.reserve();
        var lower=new DebugRequestBudget(path,5);
        assertEquals(Integer.MAX_VALUE,lower.remaining());assertEquals(10,lower.snapshot().reserved());
        assertNotNull(lower.reserve());
        assertEquals(first,lower.reserveEffect("1".repeat(64),"2".repeat(64),"original","plan"));
        assertEquals(11,new DebugRequestBudget(path,5).snapshot().reserved());
    }
    @Test void retiredLimitValuesAreIgnored() {
        for(int limit:new int[]{0,-1,100001})assertNotNull(new DebugRequestBudget(temp.resolve("invalid.json"),limit).reserve());
    }
    @Test void sendsBeyondOldCapRemainAvailableAndFailuresAreCounted() throws Exception {
        var hits=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{int call=hits.incrementAndGet();byte[] body="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(call==3?500:200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try{
            var path=temp.resolve("budget.json");var budget=new DebugRequestBudget(path,20);
            var client=new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-only-key","fake",Duration.ofSeconds(5),budget);
            var req=new ModelRequest(List.of(Map.of("role","user","content","test")),List.of());
            for(int i=0;i<20;i++)try{client.complete(req);}catch(BankException error){assertEquals("MODEL_API_ERROR",error.code);}
            assertNotNull(client.complete(req));assertEquals(21,hits.get());
            assertEquals(21,new DebugRequestBudget(path,20).snapshot().reserved());assertFalse(budget.snapshot().usageComplete());
        }finally{server.stop(0);}
    }
    @Test void corruptLedgerFailsClosed() throws Exception {var p=temp.resolve("bad.json");Files.writeString(p,"{invalid");assertThrows(BankException.class,()->new DebugRequestBudget(p,20).reserve());}
    @Test void concurrentProcessesKeepAllThirtyReservations() throws Exception {
        var p=temp.resolve("shared.json");var javaBin=Path.of(System.getProperty("java.home"),"bin");String javaExecutable=javaBin.resolve(Files.isRegularFile(javaBin.resolve("java.exe"))?"java.exe":"java").toString();String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var one=new ProcessBuilder(javaExecutable,"-cp",cp,Worker.class.getName(),p.toString()).redirectErrorStream(true).start();var two=new ProcessBuilder(javaExecutable,"-cp",cp,Worker.class.getName(),p.toString()).redirectErrorStream(true).start();
        assertTrue(one.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(two.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,one.exitValue(),new String(one.getInputStream().readAllBytes()));assertEquals(0,two.exitValue(),new String(two.getInputStream().readAllBytes()));assertEquals(30,new DebugRequestBudget(p,20).snapshot().reserved());
    }
    public static class Worker {public static void main(String[] args){var budget=new DebugRequestBudget(Path.of(args[0]),20);for(int i=0;i<15;i++)try{budget.reserve();}catch(BankException e){if(!e.code.equals("MODEL_REQUEST_BUDGET_EXCEEDED"))throw e;}}}
}
