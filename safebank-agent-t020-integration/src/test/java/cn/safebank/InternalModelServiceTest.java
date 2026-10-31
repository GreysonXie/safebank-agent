package cn.safebank;

import cn.safebank.entity.AgentServiceGrantEntity;
import cn.safebank.model.*;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalModelServiceTest {
    @TempDir Path directory;
    @Test void proxyRequiresLiveGrantMatchingTurnRevisionAndSingleOriginalSend() throws Exception {
        var settings=mock(ModelConfigurationService.class);var capabilities=mock(AgentCapabilityService.class);
        var http=mock(CompatibleModelHttp.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var grant=new AgentServiceGrantEntity();grant.setUserId("u1");grant.setTurnId("turn-one");
        when(capabilities.authorize("grant",true)).thenReturn(grant);
        var c=new ModelConnection("custom","https://example.test/v1","synthetic-secret","model",true,"revision-one");
        when(settings.connection("u1")).thenReturn(c);
        var usage=new DebugRequestBudget(directory.resolve("usage.json"),20);
        String key="a".repeat(64),hash="b".repeat(64);String reservation=usage.reserveEffect(key,hash,"turn-one","plan");
        var json=new ObjectMapper();var payload=json.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"test\"}],\"max_tokens\":512}");
        var service=new InternalModelService(settings,http,capabilities,usage,manager);
        assertThrows(BankException.class,()->service.complete("grant",new InternalModelService.Request("revision-one",payload,reservation,key,hash,"other-turn","plan")));
        assertEquals("MODEL_CONFIGURATION_CHANGED",assertThrows(BankException.class,()->service.complete("grant",new InternalModelService.Request("stale",payload,reservation,key,hash,"turn-one","plan"))).code);
        verifyNoInteractions(http);
        when(http.request(eq(c),eq("/chat/completions"),any())).thenReturn(json.createObjectNode());
        var request=new InternalModelService.Request("revision-one",payload,reservation,key,hash,"turn-one","plan");
        service.complete("grant",request);
        assertEquals("MODEL_REQUEST_UNCERTAIN",assertThrows(BankException.class,()->service.complete("grant",request)).code);
        var restarted=new InternalModelService(settings,http,capabilities,new DebugRequestBudget(directory.resolve("usage.json"),20),manager);
        assertThrows(BankException.class,()->restarted.complete("grant",request));
        verify(http,times(1)).request(eq(c),eq("/chat/completions"),any());
        when(capabilities.authorize("grant",true)).thenThrow(new BankException("DENIED","test"));
        assertThrows(BankException.class,()->service.complete("grant",request));
    }
}
