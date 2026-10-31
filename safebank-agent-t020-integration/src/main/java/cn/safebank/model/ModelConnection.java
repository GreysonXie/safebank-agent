package cn.safebank.model;

/** A volatile connection. Never serialize it into an API response, audit or checkpoint. */
public record ModelConnection(String provider,String baseUrl,String apiKey,String model,boolean enabled,String revision) {
    public boolean configured(){return enabled&&!apiKey.isBlank()&&!model.isBlank();}
    @Override public String toString(){return "ModelConnection[credentials redacted]";}
}
