package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.*;
import cn.safebank.entity.AgentContextEvidenceEntity;
import cn.safebank.common.ContextContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Service public class ContextEvidenceService {
    private final AgentContextEvidenceMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;
    public ContextEvidenceService(AgentContextEvidenceMapper mapper,ObjectMapper json,Clock clock){this.mapper=mapper;this.json=json;this.clock=clock;}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public List<EvidenceInput> load(String userId,String conversationId){
        var result=new ArrayList<EvidenceInput>();
        for(var row:mapper.selectByConversation(userId,conversationId)){
            if(row.getSnapshotJson()==null||row.getValidUntil()!=null&&!row.getValidUntil().toInstant().isAfter(clock.instant()))continue;
            if(!hash(row.getSnapshotJson()).equals(row.getContentHash()))throw new BankException("CONFLICT","业务证据完整性核验失败");
            try{result.add(new EvidenceInput(row.getEvidenceKey(),row.getToolName(),row.getObjectId(),json.readTree(row.getSnapshotJson()),row.getObservedAt().toInstant(),row.getValidUntil()==null?null:row.getValidUntil().toInstant()));}catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
        return List.copyOf(result);
    }
    @org.springframework.transaction.annotation.Transactional
    public void store(TurnLease lease,List<EvidenceInput> items){
        for(var item:items){
            if(item.data()==null||item.data().has("code")||Set.of("get_accounts","get_action_status","list_transfer_schedules").contains(item.toolName()))continue;
            var rows=mapper.selectByConversation(lease.userId(),lease.conversationId());
            var old=rows.stream().filter(r->r.getEvidenceKey().equals(item.key())).findFirst();
            var row=old.orElseGet(AgentContextEvidenceEntity::new);
            if(old.isEmpty())row.setId(UUID.randomUUID().toString());
            row.setConversationId(lease.conversationId());row.setUserId(lease.userId());row.setEvidenceKey(item.key());row.setToolName(item.toolName());row.setObjectId(item.objectId());
            row.setSnapshotJson(item.data().toString());row.setContentHash(hash(row.getSnapshotJson()));row.setSourceTurnId(lease.turnId());row.setObservedAt(item.observedAt().atOffset(ZoneOffset.UTC));row.setValidUntil(item.validUntil()==null?null:item.validUntil().atOffset(ZoneOffset.UTC));
            require(old.isEmpty()?mapper.insert(row):mapper.updateOwned(row));
        }
        var statements=mapper.selectByConversation(lease.userId(),lease.conversationId()).stream().filter(r->r.getToolName().equals("get_statement")&&r.getSnapshotJson()!=null).sorted(Comparator.comparing(AgentContextEvidenceEntity::getObservedAt).reversed().thenComparing(AgentContextEvidenceEntity::getEvidenceKey)).toList();
        for(var row:statements.stream().skip(4).toList()){row.setSnapshotJson(null);require(mapper.updateOwned(row));}
    }
    public void clear(String user,String conversation){for(var row:mapper.selectByConversation(user,conversation)){row.setSnapshotJson(null);require(mapper.updateOwned(row));}}
    public String referenceId(String user,String conversation,String key){return mapper.selectByConversation(user,conversation).stream().filter(r->r.getEvidenceKey().equals(key)).map(AgentContextEvidenceEntity::getId).findFirst().orElse(null);}
    public static void require(int changed){if(changed!=1)throw new BankException("CONFLICT","业务上下文写入冲突");}
}
