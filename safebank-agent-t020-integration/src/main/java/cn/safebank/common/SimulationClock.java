package cn.safebank.common;
import cn.safebank.BankException;
import java.time.*;
import java.util.function.Supplier;
public class SimulationClock extends Clock {
    private final Clock delegate;private final boolean enabled;private final ThreadLocal<LocalDate> businessDay=new ThreadLocal<>();
    public SimulationClock(Clock delegate,boolean enabled){this.delegate=delegate;this.enabled=enabled;}
    protected Instant baseInstant(){return delegate.instant();}
    public boolean enabled(){return enabled;}
    public Instant instant(){return baseInstant();}
    public LocalDate businessDate(){var date=businessDay.get();return date==null?instant().atZone(getZone()).toLocalDate():date;}
    public static LocalDate businessDate(Clock clock){return clock instanceof SimulationClock simulation?simulation.businessDate():clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();}
    public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
    public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
    public <T> T inBusinessDay(LocalDate date,Supplier<T> work){
        if(!enabled)throw new BankException("SIMULATION_DISABLED","模拟日期仅在独立实验实例启用");
        var prior=businessDay.get();try{businessDay.set(date);return work.get();}finally{if(prior==null)businessDay.remove();else businessDay.set(prior);}
    }
}
