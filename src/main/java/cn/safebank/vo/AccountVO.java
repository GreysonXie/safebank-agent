package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record AccountVO(String id,String label,@JsonProperty("balance_minor") long balanceMinor) {
}
