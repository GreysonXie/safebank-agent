package cn.safebank;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
public final class MutableRiskClock extends cn.safebank.common.SimulationClock {
    private final AtomicReference<Instant> now;
    public MutableRiskClock(Instant now){super(Clock.systemUTC(),true);this.now=new AtomicReference<>(now);}
    public void set(Instant value){now.set(value);}
    public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
    public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
    @Override protected Instant baseInstant(){return now.get();}
}
