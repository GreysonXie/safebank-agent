package cn.safebank.verification;
import cn.safebank.common.RiskTypes.AuthorizationAssurance;
public record ProviderVerificationResult(boolean valid,AuthorizationAssurance assurance,String providerReference) {}
