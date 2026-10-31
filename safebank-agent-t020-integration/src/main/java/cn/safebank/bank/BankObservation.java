package cn.safebank.bank;
import java.time.Instant;
public record BankObservation(String actionId, BankState state, String receiptId, String errorCode, Instant observedAt) {
    public enum BankState { PENDING, UNKNOWN, VERIFIED_SUCCESS, VERIFIED_FAILED }
}
