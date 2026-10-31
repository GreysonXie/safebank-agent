package cn.safebank.vo;
import com.fasterxml.jackson.annotation.JsonProperty;
public record RecipientVO(String id, String name, String note, String label) {
}
