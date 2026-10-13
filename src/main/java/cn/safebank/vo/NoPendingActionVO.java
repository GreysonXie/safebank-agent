package cn.safebank.vo;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
@JsonSerialize(using=cn.safebank.converter.EmptyPendingActionSerializer.class) public record NoPendingActionVO() implements PendingActionVO {
    public static final NoPendingActionVO INSTANCE=new NoPendingActionVO();
}
