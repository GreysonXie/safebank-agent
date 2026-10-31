package cn.safebank.vo;

/**
 * 模型配置视图：密钥明文永不出现在响应里。
 * hasApiKey 表示是否已保存密钥；apiKeyHint 只给末四位（不足 8 位时只给省略号），供界面显示"已保存"。
 */
public record ModelSettingsVO(String provider,String baseUrl,String model,boolean hasApiKey,String apiKeyHint,
        boolean enabled,boolean verified,String verifiedAt,String revision,String source) {}
