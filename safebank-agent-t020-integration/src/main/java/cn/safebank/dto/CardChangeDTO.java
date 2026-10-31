package cn.safebank.dto;

import jakarta.validation.constraints.*;

/** Parameters are saved in a preview and cannot be replaced during confirmation. */
public record CardChangeDTO(
        @NotNull @Pattern(regexp="APPLY|UPDATE_LIMITS|UPDATE_CONTROLS|LOCK|UNLOCK|REPORT_LOST") String kind,
        @Size(max=80) String cardId, @Size(max=80) String accountId,
        @Size(max=40) String label, @Pattern(regexp="DEBIT|VIRTUAL") String cardType,
        @Min(1) @Max(10000000) Long perTransactionLimitMinor,
        @Min(1) @Max(10000000) Long dailyLimitMinor,
        Boolean onlineEnabled, Boolean overseasEnabled) {}
