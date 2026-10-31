package cn.safebank;

import cn.safebank.dto.ModelSettingsRequestDTO;
import cn.safebank.model.*;
import cn.safebank.service.ModelConfigurationService;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigurationTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final CompatibleModelHttp http=new CompatibleModelHttp(json);
    final MockEnvironment environment=new MockEnvironment().withProperty("bank.model.api-key","");
    HttpServer server;
    final AtomicInteger calls=new AtomicInteger();
    final AtomicInteger status=new AtomicInteger(200);
    final AtomicReference<JsonNode> received=new AtomicReference<>();
    ModelConfigurationService service(){return new ModelConfigurationService(json,http,environment,Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneOffset.UTC),directory.toString());}
    ModelSettingsRequestDTO request(String base,String key,String model){return new ModelSettingsRequestDTO("custom",base,key,model,true,false);}
    String start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1",e->{
            calls.incrementAndGet();assertEquals("Bearer synthetic-test-key",e.getRequestHeaders().getFirst("Authorization"));
            String body;
            if(status.get()!=200)body="private-provider-error";
            else if(e.getRequestURI().getPath().equals("/v1/models"))body="{\"data\":[{\"id\":\"qwen-test\"},{\"id\":\"gpt-test\"},{\"id\":\"qwen-test\"}]}";
            else {received.set(json.readTree(e.getRequestBody()));body="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}";}
            var bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    @Test void presetsOnlyIncludeCurrentModelsThatAreExplicitlyMaintained(){
        var providers=service().providers().stream().collect(java.util.stream.Collectors.toMap(p->p.id(),p->p.models()));
        assertEquals(List.of("deepseek-flash","deepseek-v4-pro"),providers.get("deepseek"));
        assertEquals(List.of("gpt-6.1","gpt-6","gpt-5.6"),providers.get("openai"));
        assertTrue(providers.entrySet().stream().filter(e->!Set.of("deepseek","openai").contains(e.getKey())).allMatch(e->e.getValue().isEmpty()));
    }
    @Test void keysAreEncryptedAcrossRestartAndIsolatedByUser() throws Exception {
        var s=service();var r=request("https://example.test/v1","synthetic-secret","model-a");
        var saved=s.save("u1",r);assertTrue(saved.hasApiKey());assertFalse(saved.verified());
        assertFalse(json.writeValueAsString(saved).contains("synthetic-secret"));
        Path file;try(var files=Files.list(directory)){file=files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();}
        assertFalse(Files.readString(file).contains("synthetic-secret"));
        var restarted=service();assertEquals("synthetic-secret",restarted.connection("u1").apiKey());assertFalse(restarted.settings("u2").hasApiKey());
        restarted.save("u2",request("https://example.test/v1","other-secret","model-b"));
        assertEquals("synthetic-secret",restarted.connection("u1").apiKey());
        Path other;try(var files=Files.list(directory)){other=files.filter(p->p.toString().endsWith(".json")&&!p.equals(file)).findFirst().orElseThrow();}
        Files.copy(file,other,StandardCopyOption.REPLACE_EXISTING);
        assertEquals("MODEL_SETTINGS_UNAVAILABLE",assertThrows(BankException.class,()->service().connection("u2")).code);
    }
    @Test void blankKeyIsKeptWhileTheDestinationHostIsUnchangedAndOnlyTheTailIsShown(){
        var s=service();
        var first=s.save("u1",request("https://example.test/v1","synthetic-secret","a"));
        assertTrue(first.hasApiKey());assertEquals("…cret",first.apiKeyHint(),"只回末四位");
        // 同一主机：换路径、换模型名、换供应商标签都沿用已保存密钥，不需要重新粘贴
        s.save("u1",request("https://example.test/v1/chat/completions","","b"));
        assertEquals("synthetic-secret",s.connection("u1").apiKey());
        s.save("u1",new ModelSettingsRequestDTO("openai","https://example.test/v1","","b",true,false));
        assertEquals("synthetic-secret",s.connection("u1").apiKey());
        // 换主机：不得把旧密钥发到别的目的地
        assertThrows(BankException.class,()->s.save("u1",request("https://other.test/v1","","b")));
        // 明确清除密钥
        assertThrows(BankException.class,()->s.save("u1",new ModelSettingsRequestDTO("custom","https://example.test/v1","","b",true,true)));
        var view=s.settings("u1");
        assertFalse(json.valueToTree(view).toString().contains("synthetic-secret"),"视图里不能出现明文密钥");
        assertEquals("…cret",view.apiKeyHint());
    }
    @Test void shortKeysNeverLeakTheirTail(){
        var s=service();
        var view=s.save("u1",request("https://example.test/v1","abc1234","a"));
        assertEquals("…",view.apiKeyHint(),"不足 8 位时连尾号也不回");
        assertFalse(json.valueToTree(view).toString().contains("abc1234"));
    }
    @Test void connectivityTestReportsWhichKeyItUsed() throws Exception {
        String base=start();var s=service();
        s.save("u1",request(base,"synthetic-test-key","gpt-test"));
        // 留空测试：沿用已保存密钥，不需要重新粘贴（本地服务会校验 Authorization 头）
        var reused=s.test("u1",request(base,"","gpt-test"));
        assertEquals(Boolean.TRUE,reused.get("valid"));
        assertEquals("saved",reused.get("keySource"));
        assertEquals("…-key",reused.get("keyHint"));
        assertEquals("127.0.0.1",reused.get("host"));
        // 填写新密钥：使用本次输入
        var typed=s.test("u1",request(base,"synthetic-test-key","gpt-test"));
        assertEquals("input",typed.get("keySource"));
    }
    @Test void discoversDeduplicatedLiveModelsAndOnlyMarksExactTestedConfigurationVerified() throws Exception {
        String base=start();var s=service();var r=request(base,"synthetic-test-key","gpt-test");
        assertEquals(List.of("gpt-test","qwen-test"),s.discover("u1",request(base,"synthetic-test-key","")));
        assertEquals(true,s.test("u1",r).get("valid"));assertEquals("gpt-test",received.get().path("model").asText());
        var saved=s.save("u1",r);assertTrue(saved.verified());assertTrue(service().settings("u1").verified());
        var changed=s.save("u1",request(base,"","qwen-test"));assertFalse(changed.verified());assertNotEquals(saved.revision(),changed.revision());
        assertFalse(s.disable("u1").enabled());assertFalse(s.connection("u1").configured());
        assertEquals(2,calls.get(),"Saving/disabling must never contact a provider");
    }
    @Test void providerErrorsAreSanitizedAndCannotMarkConfigurationVerified() throws Exception {
        String base=start();status.set(401);var s=service();var r=request(base,"synthetic-test-key","gpt-test");
        var error=assertThrows(BankException.class,()->s.test("u1",r));assertEquals("MODEL_API_ERROR",error.code);
        assertFalse(error.getMessage().contains("private-provider-error"));assertFalse(error.getMessage().contains("synthetic-test-key"));
        assertFalse(s.save("u1",r).verified());
    }
    @Test void addressValidationRejectsCredentialsAndInsecureRemoteHosts(){
        for(String value:List.of("http://example.test/v1","https://key@example.test/v1","https://example.test/v1?key=secret","https://example.test/v1#secret","file:///tmp/model","not a URL"))
            assertEquals("MODEL_CONFIGURATION_INVALID",assertThrows(BankException.class,()->CompatibleModelHttp.normalizeBase(value)).code);
        assertEquals("https://example.test/v1",CompatibleModelHttp.normalizeBase(" https://example.test/v1/models/ "));
    }
    @Test void outboundRequestsRejectPrivateAndReservedDestinationsButAllowLoopbackDevelopment(){
        for(String host:List.of("10.1.2.3","172.20.1.2","192.168.1.2","169.254.1.2","100.64.0.1","224.0.0.1","[::]","[fc00::1]","[fe80::1]","[ff02::1]")){
            assertEquals("MODEL_CONFIGURATION_INVALID",assertThrows(BankException.class,()->CompatibleModelHttp.validatePublicDestination(URI.create("https://"+host+"/v1/models")),host).code);
        }
        assertDoesNotThrow(()->CompatibleModelHttp.validatePublicDestination(URI.create("http://127.0.0.1:9000/v1/models")));
        assertDoesNotThrow(()->CompatibleModelHttp.validatePublicDestination(URI.create("https://example.com/v1/models")));
    }
    @Test void openaiAndDomesticRequestOptionsAreSeparated(){
        var openai=http.body(new ModelConnection("openai","https://api.openai.com/v1","test","gpt-4.1",true,"x"),List.of(),List.of(),512,false);
        assertTrue(openai.has("max_completion_tokens"));assertFalse(openai.has("max_tokens"));assertFalse(openai.has("thinking"));
        var domestic=http.body(new ModelConnection("deepseek","https://api.deepseek.com","test","deepseek-chat",true,"x"),List.of(),List.of(),512,false);
        assertEquals("disabled",domestic.path("thinking").path("type").asText());assertTrue(domestic.has("max_tokens"));
    }
    @Test void bodyTimeoutAlsoBoundsAProviderThatSendsHeadersThenStalls() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/models",e->{try{e.sendResponseHeaders(200,100);e.getResponseBody().write('{');e.getResponseBody().flush();Thread.sleep(700);}catch(Exception ignored){}finally{e.close();}});server.start();
        long start=System.nanoTime();var c=new ModelConnection("custom","http://127.0.0.1:"+server.getAddress().getPort(),"test","test",true,"x");
        assertEquals("MODEL_UNAVAILABLE",assertThrows(BankException.class,()->http.request(c,"/models",null,Duration.ofMillis(120))).code);
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<650);
    }
}
