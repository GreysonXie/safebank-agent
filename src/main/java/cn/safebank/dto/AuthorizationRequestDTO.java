package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record AuthorizationRequestDTO(@NotBlank String payloadHash,String decisionId,boolean userConfirmed,String verificationProofId) {
}
