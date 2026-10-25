package cn.safebank.dto;
import jakarta.validation.constraints.*;
public record ScheduleManageRequestDTO(@NotBlank @Pattern(regexp="EDIT|PAUSE|RESUME|CANCEL") String operation,
        String sourceId,String recipientId,Long amountMinor,String firstDate,String endDate,Integer dayOfMonth) {}
