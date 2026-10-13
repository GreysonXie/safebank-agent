package cn.safebank.verification;
public record DeliveryResult(boolean delivered,String providerReference,String failureCode) {}
