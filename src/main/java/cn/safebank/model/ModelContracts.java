package cn.safebank.model;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
public final class ModelContracts {
    private ModelContracts() {}
    public record ModelRequest(List<Map<String,Object>> messages, List<Object> tools) {}
    /** Fixed categories only: never expose arbitrary provider strings in logs or API responses. */
    public enum FinishReason {
        STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, INSUFFICIENT_SYSTEM_RESOURCE, ABORTED, UNKNOWN;
        public static FinishReason fromProvider(JsonNode reason) {
            if(reason==null||!reason.isTextual())return UNKNOWN;
            return switch(reason.asText()) {
                case "stop" -> STOP;
                case "tool_calls" -> TOOL_CALLS;
                case "length" -> LENGTH;
                case "content_filter" -> CONTENT_FILTER;
                case "insufficient_system_resource" -> INSUFFICIENT_SYSTEM_RESOURCE;
                case "aborted" -> ABORTED;
                default -> UNKNOWN;
            };
        }
    }
    public record ModelReply(JsonNode message, JsonNode usage, FinishReason finishReason) {
        public ModelReply { if(finishReason==null)finishReason=FinishReason.UNKNOWN; }
        /** Convenience for local scripted clients; HTTP responses always use explicit provider status. */
        public ModelReply(JsonNode message,JsonNode usage) {
            this(message,usage,message!=null&&message.path("tool_calls").isArray()&&!message.path("tool_calls").isEmpty()
                ?FinishReason.TOOL_CALLS:FinishReason.STOP);
        }
        public boolean completed(){return finishReason==FinishReason.STOP||finishReason==FinishReason.TOOL_CALLS;}
    }
}
