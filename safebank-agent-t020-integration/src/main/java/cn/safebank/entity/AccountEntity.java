package cn.safebank.entity;
/** A persisted account; ownership is never provided by a request body. */
public class AccountEntity {
    private String id;
    private String userId;
    private String label;
    private Long balanceMinor;
    public String getId(){
        return id;
    }
    public void setId(String value){
        id=value;
    }
    public String getUserId(){
        return userId;
    }
    public void setUserId(String value){
        userId=value;
    }
    public String getLabel(){
        return label;
    }
    public void setLabel(String value){
        label=value;
    }
    public Long getBalanceMinor(){
        return balanceMinor;
    }
    public void setBalanceMinor(Long value){
        balanceMinor=value;
    }
}
