package cn.safebank;
import cn.safebank.verification.*;
import cn.safebank.common.RiskTypes.*;
import java.util.concurrent.ConcurrentHashMap;
public class CapturingVerificationProvider implements VerificationProvider {
    private final ConcurrentHashMap<String,String> codes=new ConcurrentHashMap<>();
    public boolean failDelivery;
    public DeliveryResult deliver(VerificationDelivery delivery){
        if(failDelivery)return new DeliveryResult(false,null,"UNAVAILABLE");
        codes.put(delivery.challengeId(),delivery.oneTimeCode());
        return new DeliveryResult(true,delivery.challengeId(),null);
    }
    public ProviderVerificationResult verify(VerificationAttempt attempt){return new ProviderVerificationResult(false,null,null);}
    public String code(String challengeId){return codes.get(challengeId);}
}
