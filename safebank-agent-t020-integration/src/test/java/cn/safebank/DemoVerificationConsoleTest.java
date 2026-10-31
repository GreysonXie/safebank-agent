package cn.safebank;
import cn.safebank.verification.*;
import cn.safebank.config.VerificationProperties;
import cn.safebank.common.RiskTypes.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.net.*;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class DemoVerificationConsoleTest {
    @Test void onlyIndependentOperatorCanReadCode() throws Exception {
        var props=new VerificationProperties();props.setDemoEnabled(true);props.setOperatorToken(java.util.UUID.randomUUID().toString());props.setConsolePort(0);
        var provider=new DemoOtpProvider(Clock.systemUTC());
        provider.deliver(new VerificationDelivery("demo","u1",VerificationMethod.DEMO_OTP,"824591",Instant.now().plusSeconds(60)));
        try(var console=new DemoVerificationConsole(props,provider,new com.fasterxml.jackson.databind.ObjectMapper())){
            console.start();var uri=URI.create("http://127.0.0.1:"+console.port()+"/challenges/demo");var client=HttpClient.newHttpClient();
            assertEquals(403,client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var response=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+props.getOperatorToken()).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertTrue(response.body().contains("824591"));
        }
    }
    @Test void consoleDisabledByDefault(){var props=new VerificationProperties();try(var console=new DemoVerificationConsole(props,new DemoOtpProvider(Clock.systemUTC()),new com.fasterxml.jackson.databind.ObjectMapper())){assertThrows(IllegalStateException.class,console::start);}}
    @Test void pastAndFuturePeriodsUseActualTwoMinuteConsoleLifetime() throws Exception {
        for(LocalDate date:new LocalDate[]{LocalDate.of(2026,9,9),LocalDate.of(2026,11,9)}){
            var clock=new MutableRiskClock(Instant.parse("2026-10-09T04:00:00Z"));var props=new VerificationProperties();props.setDemoEnabled(true);props.setOperatorToken(java.util.UUID.randomUUID().toString());props.setConsolePort(0);var provider=new DemoOtpProvider(clock);
            clock.inBusinessDay(date,()->provider.deliver(new VerificationDelivery("period","u1",VerificationMethod.DEMO_OTP,"824591",clock.instant().plusSeconds(120))));
            try(var console=new DemoVerificationConsole(props,provider,new com.fasterxml.jackson.databind.ObjectMapper())){
                console.start();var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+console.port()+"/challenges/period")).header("Authorization","Bearer "+props.getOperatorToken()).GET().build();var client=HttpClient.newHttpClient();
                assertEquals(200,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());clock.set(Instant.parse("2026-10-09T04:02:01Z"));assertEquals(404,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
    }
}
