package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.agent.*;
import cn.safebank.dto.*;
import cn.safebank.model.*;
import static cn.safebank.model.ModelContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:contextAgent;DB_CLOSE_DELAY=-1")
class ContextAgentConversationTest extends BankTestFixture {
    @Autowired ContextService contexts;@Autowired ContextAgentSupport support;@Autowired ClarificationService clarification;@Autowired ObjectMapper json;
    @Test void freshHttpSessionCanContinueDiscussionAfterSixTurns(){
        int[] generated={0};ModelClient client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest r){
                if(json.valueToTree(r.tools()).path(0).path("function").path("name").asText().equals("review_business_answer"))return call("review_business_answer",Map.of("approved",true,"issues",List.of(),"checks",Map.of("revocation_boundary",true,"accounting_boundary",true,"risk_boundary",true,"query_coverage",true,"question_focus",true)));
                if(generated[0]++==0)return call("get_statement",Map.of("month","2026-09"));
                assertTrue(r.messages().toString().contains("2026-09"));
                return call("answer_customer",Map.of("answer","{{statement_2026_09.month}} 的账单可继续核对；维修报价和凭证需要你补充。","kind","analysis"));
            }
            private ModelReply call(String name,Map<String,Object> args){try{return new ModelReply(json.valueToTree(Map.of("role","assistant","tool_calls",List.of(Map.of("id",UUID.randomUUID().toString(),"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(args)))))),null);}catch(Exception e){throw new IllegalStateException(e);}}
        };
        var coordinator=new ContextAgentCoordinator(contexts,support,new AgentService(gateway,json,client),clarification);
        var result=coordinator.chat("u1",new ChatRequestDTO("分析2026-09账单",5,null,"first",null),new MockHttpSession());
        String id=result.get("conversationId").toString();
        for(int i=0;i<5;i++){var v=contexts.read("u1",id);result=coordinator.chat("u1",new ChatRequestDTO("接着核对维修那笔",5,id,"follow"+i,v.version()),new MockHttpSession());}
        assertEquals("2026-09",contexts.read("u1",id).slots().get("primary_month").asText());assertEquals("MODEL_GROUNDED",result.get("answerSource"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void offlinePauseCannotCreateTransferPreview(){
        var client=new ModelClient(){public String mode(){return "OFFLINE_DEMO";}public String modelName(){return "offline";}public ModelReply complete(ModelRequest r){throw new AssertionError("No supplier call in offline mode");}};
        var coordinator=new ContextAgentCoordinator(contexts,support,new AgentService(gateway,json,client),clarification);
        var result=coordinator.chat("u1",new ChatRequestDTO("先别给同学转账500元",5,null,"offlinePause",null),new MockHttpSession());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
}
