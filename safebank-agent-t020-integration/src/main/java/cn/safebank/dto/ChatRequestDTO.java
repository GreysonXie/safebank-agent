package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record ChatRequestDTO(@NotBlank @Size(max=4000) String text,@Min(1) @Max(12) Integer modelRequestLimit,
    @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String conversationId,
    @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String requestId,@Min(1) Long expectedContextVersion) {
    public ChatRequestDTO(String text,Integer modelRequestLimit){this(text,modelRequestLimit,null,null,null);}
}
