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
    @Test void everySendIncludingFailedRequestIsCountedAndTwentyFirstIsBlocked() throws Exception {
        var hits=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",e->{int call=hits.incrementAndGet();byte[] body="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(call==3?500:200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try{
            var path=temp.resolve("budget.json");var budget=new DebugRequestBudget(path,20);
            var client=new DeepSeekModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-only-key","fake",Duration.ofSeconds(5),budget);
            var req=new ModelRequest(List.of(Map.of("role","user","content","test")),List.of());
            for(int i=0;i<20;i++)try{client.complete(req);}catch(BankException error){assertEquals("MODEL_API_ERROR",error.code);}
            assertEquals("MODEL_REQUEST_BUDGET_EXCEEDED",assertThrows(BankException.class,()->client.complete(req)).code);assertEquals(20,hits.get());
            assertEquals(0,new DebugRequestBudget(path,20).remaining());assertFalse(budget.snapshot().usageComplete());
        }finally{server.stop(0);}
    }
    @Test void corruptLedgerFailsClosed() throws Exception {var p=temp.resolve("bad.json");Files.writeString(p,"{invalid");assertThrows(BankException.class,()->new DebugRequestBudget(p,20).reserve());}
    @Test void twoIndependentProcessesCannotSpendMoreThanTwentyReservations() throws Exception {
        var p=temp.resolve("shared.json");String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var one=new ProcessBuilder(javaExecutable,"-cp",cp,Worker.class.getName(),p.toString()).redirectErrorStream(true).start();var two=new ProcessBuilder(javaExecutable,"-cp",cp,Worker.class.getName(),p.toString()).redirectErrorStream(true).start();
        assertTrue(one.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(two.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,one.exitValue(),new String(one.getInputStream().readAllBytes()));assertEquals(0,two.exitValue(),new String(two.getInputStream().readAllBytes()));assertEquals(0,new DebugRequestBudget(p,20).remaining());
    }
    public static class Worker {public static void main(String[] args){var budget=new DebugRequestBudget(Path.of(args[0]),20);for(int i=0;i<15;i++)try{budget.reserve();}catch(BankException e){if(!e.code.equals("MODEL_REQUEST_BUDGET_EXCEEDED"))throw e;}}}
}
