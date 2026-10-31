package cn.safebank.verification;
import cn.safebank.config.VerificationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
public class DemoVerificationConsole implements AutoCloseable {
    private final VerificationProperties props;private final DemoOtpProvider provider;private final ObjectMapper json;private HttpServer server;
    public DemoVerificationConsole(VerificationProperties props,DemoOtpProvider provider,ObjectMapper json){this.props=props;this.provider=provider;this.json=json;}
    public void start(){
        if(!props.isDemoEnabled()||props.getOperatorToken().length()<24)throw new IllegalStateException("Independent demo operator configuration required");
        try{
            server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),props.getConsolePort()),0);
            server.createContext("/challenges/",this::handle);server.start();
        }catch(java.io.IOException e){throw new IllegalStateException("Cannot start independent demo console",e);}
    }
    private void handle(HttpExchange exchange) throws java.io.IOException {
        try{
            String supplied=exchange.getRequestHeaders().getFirst("Authorization");
            boolean valid=supplied!=null&&MessageDigest.isEqual(("Bearer "+props.getOperatorToken()).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
            if(!valid||!"GET".equals(exchange.getRequestMethod())){exchange.sendResponseHeaders(403,-1);return;}
            String id=exchange.getRequestURI().getPath().substring("/challenges/".length());
            if(!id.matches("[A-Za-z0-9-]{1,80}")){exchange.sendResponseHeaders(404,-1);return;}
            var delivery=provider.operatorDelivery(id);
            if(delivery==null){exchange.sendResponseHeaders(404,-1);return;}
            byte[] body=json.writeValueAsBytes(Map.of("challengeId",id,"code",delivery.oneTimeCode(),"verificationMode","LOCAL_DEMO_ONLY"));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }finally{exchange.close();}
    }
    public int port(){return server.getAddress().getPort();}
    public void close(){if(server!=null)server.stop(0);}
}
