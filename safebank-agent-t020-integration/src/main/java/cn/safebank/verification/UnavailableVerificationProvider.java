package cn.safebank.verification;
public class UnavailableVerificationProvider implements VerificationProvider {
    public DeliveryResult deliver(VerificationDelivery delivery){return new DeliveryResult(false,null,"VERIFICATION_UNAVAILABLE");}
    public ProviderVerificationResult verify(VerificationAttempt attempt){return new ProviderVerificationResult(false,null,null);}
}
