package cn.safebank.dto;

import jakarta.validation.constraints.*;

public record ModelSettingsRequestDTO(@NotBlank @Size(max=40) String provider,
        @NotBlank @Size(max=2048) String baseUrl, @Size(max=4096) String apiKey,
        @Size(max=200) String model, Boolean enabled, boolean clearApiKey) {}
