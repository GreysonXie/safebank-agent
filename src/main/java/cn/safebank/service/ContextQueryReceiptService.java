package cn.safebank.service;
import cn.safebank.dao.AgentTurnReceiptMapper;
import cn.safebank.common.ContextContracts.*;
import cn.safebank.vo.MandateVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service public class ContextQueryReceiptService {
    private final AgentTurnReceiptMapper turns;private final ContextEvidenceService evidence;private final ObjectMapper json;
    public ContextQueryReceiptService(AgentTurnReceiptMapper t,ContextEvidenceService e,ObjectMapper j){turns=t;evidence=e;json=j;}
    public void capture(String user,String actionId,List<MandateVO> data,Instant queriedAt){
        for(var t:turns.selectWithBankReferences(user)){
            List<BankReference> refs;try{refs=json.readValue(t.getBankReferencesJson(),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Bank reference is corrupt",e);}
            if(refs.stream().noneMatch(r->r.kind().equals("ACTION")&&r.bankObjectId().equals(actionId)))continue;
            var lease=new TurnLease(user,t.getConversationId(),t.getId(),t.getInputVersion(),t.getRequestId());
            evidence.store(lease,List.of(new EvidenceInput("query_mandates:"+actionId,"list_mandates",actionId,json.valueToTree(data),queriedAt,null)));
        }
    }
    public Optional<EvidenceInput> readOriginal(String user,String conversation,String actionId){return evidence.load(user,conversation).stream().filter(e->e.key().equals("query_mandates:"+actionId)).findFirst();}
}
