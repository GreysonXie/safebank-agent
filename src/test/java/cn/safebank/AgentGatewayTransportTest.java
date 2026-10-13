package cn.safebank;
import cn.safebank.agent.HttpAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
class AgentGatewayTransportTest {
    @Test void acknowledgedClarificationIsAcceptedWithOriginalHashAndVersion()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/agent/turns/t/ack",e->{byte[] b="{\"state\":\"NEEDS_CLARIFICATION\",\"resultHash\":\"h\",\"outputVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        try{var client=new HttpAgentClient("http://127.0.0.1:"+server.getAddress().getPort(),"x".repeat(64),new ObjectMapper());assertDoesNotThrow(()->client.ack(new cn.safebank.vo.AgentAckVO("t",2,"h",java.util.Map.of())));}finally{server.stop(0);}
    }
    @Test void timeoutCoversStalledBodyAfterSuccessfulHeaders()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newCachedThreadPool();server.setExecutor(workers);
        server.createContext("/internal/agent/turns/t",exchange->{
            byte[] body="{\"state\":\"NO_RUN\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body,0,1);exchange.getResponseBody().flush();
            try{Thread.sleep(7000);exchange.getResponseBody().write(body,1,body.length-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();
        try{var client=new HttpAgentClient("http://127.0.0.1:"+server.getAddress().getPort(),"x".repeat(64),new ObjectMapper());long before=System.nanoTime();assertThrows(HttpAgentClient.AgentTransportException.class,()->client.get("t"));assertTrue((System.nanoTime()-before)/1_000_000<6000,"Whole response must have a bounded deadline");}
        finally{server.stop(0);workers.shutdownNow();}
    }
    @Test void oversizedResponseIsRejectedBeforeUnboundedBuffering()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/internal/agent/turns/t",exchange->{byte[] bytes=new byte[65537];exchange.sendResponseHeaders(200,bytes.length);try{exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        try{var client=new HttpAgentClient("http://127.0.0.1:"+server.getAddress().getPort(),"x".repeat(64),new ObjectMapper());assertThrows(HttpAgentClient.AgentTransportException.class,()->client.get("t"));}finally{server.stop(0);}
    }
}
