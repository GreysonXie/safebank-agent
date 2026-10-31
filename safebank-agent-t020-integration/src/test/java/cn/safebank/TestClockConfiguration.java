package cn.safebank;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.time.*;
@TestConfiguration
class TestClockConfiguration {
    @Bean @Primary Clock fixedBankClock() { return new cn.safebank.common.SimulationClock(Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"),ZoneId.of("Asia/Shanghai")),true); }
}
