package cn.safebank.vo;

public record ModelSettingsVO(String provider,String baseUrl,String model,boolean hasApiKey,
        boolean enabled,boolean verified,String verifiedAt,String revision,String source) {}
