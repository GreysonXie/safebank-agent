package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonIgnore;
public sealed interface PendingActionVO permits ActionVO,NoPendingActionVO {
    @JsonIgnore default boolean isEmpty(){
        return this instanceof NoPendingActionVO;
    }
}
