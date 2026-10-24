package cn.safebank.dto;

import jakarta.validation.constraints.*;

public record UserProfileRequestDTO(
        @NotBlank @Size(max=80) String name,
        @NotNull @Pattern(regexp="(?:1[0-9]{10})?") String phone,
        @NotNull @Pattern(regexp="UNKNOWN|MALE|FEMALE|UNDISCLOSED") String gender) {}
