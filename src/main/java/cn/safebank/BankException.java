package cn.safebank;
public class BankException extends RuntimeException {
    public final String code;
    public BankException(String code,String message) { super(message); this.code=code; }
}
