package cn.safebank.model;

import cn.safebank.BankException;
import com.fasterxml.jackson.databind.*;
import static cn.safebank.model.ModelContracts.*;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Per-thread request budget and provider-reported usage; never logs prompts or secrets. */
public final class ModelUsageMeter implements ModelClient {
    private final ModelClient delegate;
    private final ObjectMapper json;
    private final ThreadLocal<Ledger> current=new ThreadLocal<>();
    private static final List<String> TOKENS=List.of("prompt_tokens","completion_tokens","total_tokens");
    private static final List<String> CACHE=List.of("prompt_cache_hit_tokens","prompt_cache_miss_tokens");
    private record Ledger(int limit,List<Map<String,Object>> calls) {}
    public ModelUsageMeter(ObjectMapper json,ModelClient delegate){this.json=json;this.delegate=delegate;}
    public String mode(){return delegate.mode();}
    public String modelName(){return delegate.modelName();}
    public void begin(int limit){
        if(limit<1||limit>12)throw new BankException("VALIDATION_ERROR","每回合模型请求上限须为1至12");
        current.set(new Ledger(limit,new ArrayList<>()));
    }
    public void clear(){current.remove();}
    public boolean hasRemaining(){return hasRemaining(1);}
    public boolean hasRemaining(int required){
        if(required<1)throw new IllegalArgumentException("Required requests must be positive");
        var ledger=current.get();return ledger!=null&&ledger.limit()-ledger.calls().size()>=required&&delegate.availableRequests()>=required;
    }
    public ModelReply complete(ModelRequest request){
        var ledger=current.get();
        if(!hasRemaining())throw new BankException("MODEL_TURN_BUDGET_EXCEEDED","本轮请求次数已达上限，未继续调用供应商");
        var entry=new LinkedHashMap<String,Object>();
        boolean review=json.valueToTree(request.tools()).path(0).path("function").path("name").asText().equals("review_business_answer");
        entry.put("kind",review?"review":"generation");entry.put("success",false);ledger.calls().add(entry);
        try{
            var result=delegate.complete(request);entry.put("success",true);
            entry.put("finish_reason",result.finishReason().name());
            entry.put("response_complete",result.completed());
            for(String field:TOKENS)copyToken(result.usage(),field,entry);
            for(String field:CACHE)copyToken(result.usage(),field,entry);
            return result;
        }finally{LoggerFactory.getLogger(ModelUsageMeter.class).info("MODEL_USAGE {}",entry);}
    }
    private void copyToken(JsonNode usage,String field,Map<String,Object> entry){
        if(usage==null)return;JsonNode value=usage.path(field);
        if(value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=0)entry.put(field,value.longValue());
    }
    public Map<String,Object> snapshot(){
        var ledger=current.get();if(ledger==null)throw new IllegalStateException("No active usage scope");
        var result=new LinkedHashMap<String,Object>();var calls=ledger.calls();
        long reported=calls.stream().filter(c->c.keySet().containsAll(TOKENS)).count();
        result.put("request_count",calls.size());result.put("request_limit",ledger.limit());
        result.put("generation_requests",calls.stream().filter(c->c.get("kind").equals("generation")).count());
        result.put("review_requests",calls.stream().filter(c->c.get("kind").equals("review")).count());
        result.put("usage_reported_requests",reported);result.put("usage_complete",reported==calls.size());
        for(String field:TOKENS)result.put(field,reported==calls.size()?sum(calls,field):null);
        boolean cacheComplete=calls.stream().allMatch(c->c.keySet().containsAll(CACHE));
        for(String field:CACHE)result.put(field,cacheComplete?sum(calls,field):null);
        result.put("calls",calls.stream().map(Map::copyOf).toList());return result;
    }
    private long sum(List<Map<String,Object>> calls,String field){return calls.stream().mapToLong(c->((Number)c.getOrDefault(field,0L)).longValue()).sum();}
}
