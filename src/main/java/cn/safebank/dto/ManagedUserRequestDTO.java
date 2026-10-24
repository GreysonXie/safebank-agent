package cn.safebank.dto;

import jakarta.validation.constraints.*;

public record ManagedUserRequestDTO(
        @NotBlank @Size(max=80) String name,
        @NotNull @Pattern(regexp="(?:1[0-9]{10}|TEST-PHONE-[0-9]{4})?") String phone,
        @NotNull @Pattern(regexp="UNKNOWN|MALE|FEMALE|UNDISCLOSED") String gender,
        @NotNull @Size(max=120) String note,
        boolean savingsTarget,
        @Size(max=80) String accountId,
        @Min(0) @Max(100000000) long initialBalanceMinor) {}
