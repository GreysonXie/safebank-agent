package cn.safebank;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class Application {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Profile("!mysql")
    public java.time.Clock bankClock(@org.springframework.beans.factory.annotation.Value("${bank.simulation.clock-enabled:false}") boolean enabled) { return new cn.safebank.common.SimulationClock(java.time.Clock.systemUTC(),enabled); }
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
