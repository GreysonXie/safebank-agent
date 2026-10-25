package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.LocalDate;
public record ScheduleTransferVO(String scheduleId,LocalDate dueDate,@JsonUnwrapped ActionVO action) {}
