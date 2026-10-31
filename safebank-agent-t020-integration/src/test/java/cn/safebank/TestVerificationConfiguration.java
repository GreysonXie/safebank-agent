package cn.safebank;
import cn.safebank.config.VerificationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.time.*;
@TestConfiguration public class TestVerificationConfiguration {
    @Bean @Primary CapturingVerificationProvider capturingVerificationProvider(){return new CapturingVerificationProvider();}
    @Bean @Primary VerificationProperties testVerificationProperties(){
        var p=new VerificationProperties();p.setDemoEnabled(true);p.setPepper(java.util.UUID.randomUUID().toString());return p;
    }
}
