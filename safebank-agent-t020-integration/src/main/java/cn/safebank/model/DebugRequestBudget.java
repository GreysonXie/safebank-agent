package cn.safebank.model;
import cn.safebank.BankException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
/** Durable usage ledger. Project calls are unlimited; per-turn and idempotency guards remain. */
@Component public class DebugRequestBudget {
    public record BudgetSnapshot(int limit,int reserved,int remaining,boolean usageComplete,Long promptTokens,Long completionTokens,List<Map<String,Object>> calls) {}
    private static final ConcurrentHashMap<Path,ReentrantLock> JVM_LOCKS=new ConcurrentHashMap<>();
    private final ObjectMapper json=new ObjectMapper();private final Path path;private final boolean enabled;
    @Autowired public DebugRequestBudget(@Value("${bank.model.debug-budget.enabled:false}") boolean enabled,@Value("${bank.model.debug-budget.path:}") String path){
        this.enabled=enabled;
        if(enabled&&(path.isBlank()||!Path.of(path).isAbsolute()))throw new IllegalStateException("Model usage ledger requires an absolute path");
        this.path=enabled?Path.of(path).toAbsolutePath().normalize():null;
    }
    /** Historical limit argument is ignored so existing ledgers and integrations remain readable. */
    public DebugRequestBudget(Path path,int ignoredLimit){this(true,path.toAbsolutePath().normalize().toString());}
    public int remaining(){return Integer.MAX_VALUE;}
    public void requireAvailable(){}
    public String reserve(){
        if(!enabled)return null;
        return locked(root->{var calls=root.withArray("calls");String id=UUID.randomUUID().toString();var call=calls.addObject();call.put("id",id);call.put("reservedAt",Instant.now().toString());call.put("success",false);call.put("finished",false);call.putNull("usage");write(root);return id;});
    }
    public void record(String id,boolean success,JsonNode usage){
        if(id==null)return;
        locked(root->{ObjectNode match=null;for(var c:root.withArray("calls"))if(c.path("id").asText().equals(id)){match=(ObjectNode)c;break;}if(match==null)throw invalid();match.put("success",success);match.put("finished",true);match.put("finishedAt",Instant.now().toString());
            var safe=json.createObjectNode();if(usage!=null)for(String field:List.of("prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens")){var v=usage.path(field);if(v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()>=0)safe.set(field,v);}match.set("usage",safe.isEmpty()?NullNode.instance:safe);write(root);return null;});
    }
    public String reserveEffect(String key,String inputHash,String turn,String purpose){
        return reserveEffect(key,inputHash,turn,purpose,5);
    }
    public String reserveEffect(String key,String inputHash,String turn,String purpose,int effectiveLimit){
        effectIdentity(key,inputHash,turn,purpose);if(!enabled)throw invalid();
        if(effectiveLimit<1||effectiveLimit>5)throw invalid();
        return locked(root->{var calls=root.withArray("calls");int turnCount=0;
            for(var c:calls){if(c.path("operationKey").asText().equals(key)){sameEffect(c,key,inputHash,turn,purpose);return c.path("id").asText();}if(c.path("javaTurnId").asText().equals(turn))turnCount++;}
            if(turnCount>=effectiveLimit)throw new BankException("MODEL_TURN_BUDGET_EXCEEDED","本轮模型调用已达用户设置的上限，已停止发送");

            String id=UUID.randomUUID().toString();var c=calls.addObject();c.put("id",id);c.put("operationKey",key);c.put("inputHash",inputHash);c.put("javaTurnId",turn);c.put("purpose",purpose);c.put("reservedAt",Instant.now().toString());c.put("success",false);c.put("finished",false);c.putNull("usage");write(root);return id;
        });
    }
    public void recordEffect(String id,String key,String inputHash,String turn,String purpose,boolean success,JsonNode usage){
        effectIdentity(key,inputHash,turn,purpose);if(!enabled||id==null)throw invalid();
        locked(root->{ObjectNode match=null;for(var c:root.withArray("calls"))if(c.path("id").asText().equals(id)){match=(ObjectNode)c;break;}if(match==null)throw invalid();sameEffect(match,key,inputHash,turn,purpose);
            var safe=json.createObjectNode();if(usage!=null)for(String field:List.of("prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens")){var v=usage.path(field);if(v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()>=0)safe.set(field,v);}JsonNode normalized=safe.isEmpty()?NullNode.instance:safe;
            if(match.path("finished").asBoolean()){if(match.path("success").asBoolean()!=success||!match.path("usage").equals(normalized))throw invalid();return null;}
            match.put("success",success);match.put("finished",true);match.put("finishedAt",Instant.now().toString());match.set("usage",normalized);write(root);return null;
        });
    }
    /** A managed proxy send can claim a reservation once, including across process restarts. */
    public void claimEffect(String id,String key,String inputHash,String turn,String purpose){
        effectIdentity(key,inputHash,turn,purpose);if(!enabled||id==null)throw invalid();
        locked(root->{for(var node:root.withArray("calls"))if(id.equals(node.path("id").asText())){
            sameEffect(node,key,inputHash,turn,purpose);
            if(node.path("providerStarted").asBoolean()||node.path("finished").asBoolean())throw new BankException("MODEL_REQUEST_UNCERTAIN","原模型发送已开始，请核对原请求，不能重复发送");
            ((ObjectNode)node).put("providerStarted",true);write(root);return null;
        }throw invalid();});
    }
    private static void effectIdentity(String key,String h,String turn,String purpose){if(key==null||!key.matches("[a-f0-9]{64}")||h==null||!h.matches("[a-f0-9]{64}")||turn==null||!turn.matches("[A-Za-z0-9_-]{1,80}")||!Set.of("plan","draft","repair").contains(purpose))throw invalid();}
    private static void sameEffect(JsonNode c,String key,String h,String turn,String purpose){if(!c.path("operationKey").asText().equals(key)||!c.path("inputHash").asText().equals(h)||!c.path("javaTurnId").asText().equals(turn)||!c.path("purpose").asText().equals(purpose))throw invalid();}
    public BudgetSnapshot snapshot(){
        if(!enabled)return new BudgetSnapshot(Integer.MAX_VALUE,0,Integer.MAX_VALUE,false,null,null,List.of());
        return locked(root->{var calls=root.withArray("calls");boolean complete=true;long prompt=0,completion=0;var records=new ArrayList<Map<String,Object>>();
            for(var c:calls){var usage=c.path("usage");if(!c.path("finished").asBoolean()||!usage.has("prompt_tokens")||!usage.has("completion_tokens")||!usage.has("total_tokens"))complete=false;else{prompt+=usage.path("prompt_tokens").longValue();completion+=usage.path("completion_tokens").longValue();}records.add(json.convertValue(c,new TypeReference<>(){}));}
            return new BudgetSnapshot(Integer.MAX_VALUE,calls.size(),Integer.MAX_VALUE,complete,complete?prompt:null,complete?completion:null,List.copyOf(records));
        });
    }
    private <T> T locked(Function<ObjectNode,T> work){
        var local=JVM_LOCKS.computeIfAbsent(path,p->new ReentrantLock());local.lock();
        try{Files.createDirectories(path.getParent());try(var channel=FileChannel.open(path.resolveSibling(path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
            ObjectNode root;if(!Files.exists(path)){root=json.createObjectNode();root.put("unlimited",true);root.putArray("calls");}else{var parsed=json.readTree(Files.readString(path));if(!parsed.isObject()||!parsed.path("calls").isArray())throw invalid();root=(ObjectNode)parsed;var ids=new HashSet<String>();for(var c:root.withArray("calls"))if(!c.isObject()||!c.path("id").isTextual()||!ids.add(c.path("id").asText()))throw invalid();
                // Preserve old reservations while retiring their historical cap metadata.
                if(root.has("limit")||!root.path("unlimited").asBoolean()){root.remove("limit");root.put("unlimited",true);write(root);}
            }
            return work.apply(root);
        }}catch(BankException e){throw e;}catch(Exception e){throw invalid();}finally{local.unlock();}
    }
    private void write(ObjectNode root){Path tmp=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".tmp");try{Files.writeString(tmp,json.writeValueAsString(root),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw invalid();}}
    private static BankException invalid(){return new BankException("MODEL_DEBUG_BUDGET_INVALID","调试预算账本不可读取或写入，已停止供应商调用");}
}
