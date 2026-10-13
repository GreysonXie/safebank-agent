package cn.safebank.common;
import cn.safebank.BankException;
public class RiskRejectedException extends BankException {
    private final RiskFailure failure;
    public RiskRejectedException(RiskFailure failure){super(failure.code(),failure.message());this.failure=failure;}
    public RiskFailure failure(){return failure;}
    public static <T> T value(RiskOutcome<T> outcome){if(!outcome.accepted())throw new RiskRejectedException(outcome.failure());return outcome.value();}
}
