package cn.safebank.verification;
import cn.safebank.common.RiskTypes.*;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
public class DemoOtpProvider implements VerificationProvider {
    private final ConcurrentHashMap<String,VerificationDelivery> deliveries=new ConcurrentHashMap<>();
    private final Clock clock;
    public DemoOtpProvider(Clock clock){this.clock=clock;}
    public DeliveryResult deliver(VerificationDelivery d){deliveries.entrySet().removeIf(e->!e.getValue().expiresAt().isAfter(clock.instant()));deliveries.put(d.challengeId(),d);return new DeliveryResult(true,d.challengeId(),null);}
    public ProviderVerificationResult verify(VerificationAttempt attempt){return new ProviderVerificationResult(false,null,null);}
    VerificationDelivery operatorDelivery(String id){var d=deliveries.get(id);if(d==null)return null;if(!d.expiresAt().isAfter(clock.instant())){deliveries.remove(id);return null;}return d;}
}
