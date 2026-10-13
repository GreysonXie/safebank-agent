package cn.safebank.common;
public record RiskOutcome<T>(T value,RiskFailure failure) {
    public RiskOutcome { if((value==null)==(failure==null))throw new IllegalArgumentException("Exactly one risk outcome required"); }
    public boolean accepted(){return failure==null;}
    public static <T> RiskOutcome<T> success(T value){return new RiskOutcome<>(value,null);}
    public static <T> RiskOutcome<T> rejected(RiskFailure failure){return new RiskOutcome<>(null,failure);}
}
