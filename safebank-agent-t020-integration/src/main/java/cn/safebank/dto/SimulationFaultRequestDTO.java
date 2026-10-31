package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record SimulationFaultRequestDTO(@NotBlank String actionId,@NotNull cn.safebank.bank.FaultInjectingBankAdapter.FaultMode submitMode,@NotNull cn.safebank.bank.FaultInjectingBankAdapter.FaultMode lookupMode) {
}
