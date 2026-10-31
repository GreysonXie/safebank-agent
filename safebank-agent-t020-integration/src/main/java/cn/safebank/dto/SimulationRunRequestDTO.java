package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record SimulationRunRequestDTO(@NotBlank String date) {
}
