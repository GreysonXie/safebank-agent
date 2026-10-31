package cn.safebank.verification;
public interface VerificationProvider {
    DeliveryResult deliver(VerificationDelivery delivery);
    ProviderVerificationResult verify(VerificationAttempt attempt);
}
