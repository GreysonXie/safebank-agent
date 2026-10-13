package cn.safebank.bank;
public interface BankAdapter {
    BankObservation submit(String userId,String actionId);
    BankObservation lookup(String userId,String actionId);
}
