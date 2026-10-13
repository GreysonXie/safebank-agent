package cn.safebank.vo;
import com.fasterxml.jackson.annotation.*;
import java.time.Instant;
import java.util.List;
public record QueryExecutionReceiptVO(@JsonUnwrapped ActionVO action,List<MandateVO> data,Instant queriedAt) implements ActionExecutionResultVO {}
