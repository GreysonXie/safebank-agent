package cn.safebank;
import cn.safebank.service.ActionExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
public class ExecutionTestSupport {
    private final ActionExecutionService execution;private final ObjectMapper json;
    public ExecutionTestSupport(ActionExecutionService execution,ObjectMapper json) { this.execution=execution;this.json=json; }
    @SuppressWarnings("unchecked") public Map<String,Object> submit(String user,String id) { return json.convertValue(execution.submit(user,id),Map.class); }
    @SuppressWarnings("unchecked") public Map<String,Object> observe(String user,String id) { return json.convertValue(execution.observe(user,id),Map.class); }
}
