package cn.safebank.config;
import cn.safebank.verification.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Arrays;
@Configuration public class VerificationConfiguration {
    @Bean public VerificationProvider verificationProvider(VerificationProperties props,Environment env,Clock clock){
        boolean explicitDemo=Arrays.asList(env.getActiveProfiles()).contains("risk-demo");
        return explicitDemo&&props.isDemoEnabled()&&props.getPepper().length()>=24&&props.getOperatorToken().length()>=24?new DemoOtpProvider(clock):new UnavailableVerificationProvider();
    }
    @Bean(destroyMethod="close") public DemoConsoleLifecycle demoConsoleLifecycle(VerificationProvider provider,VerificationProperties props,ObjectMapper json){
        return new DemoConsoleLifecycle(provider,props,json);
    }
    public static class DemoConsoleLifecycle implements AutoCloseable {
        private DemoVerificationConsole console;
        DemoConsoleLifecycle(VerificationProvider provider,VerificationProperties props,ObjectMapper json){
            if(provider instanceof DemoOtpProvider demo){console=new DemoVerificationConsole(props,demo,json);console.start();}
        }
        public void close(){if(console!=null)console.close();}
    }
}
