package cn.safebank;

import cn.safebank.model.*;
import static cn.safebank.model.ModelContracts.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessAnswerReviewTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final List<String> CHECKS=List.of("revocation_boundary","accounting_boundary","risk_boundary","query_coverage","question_focus");
    private Map<String,Object> verdict() {
        var checks=new LinkedHashMap<String,Object>();CHECKS.forEach(key->checks.put(key,true));
        return new LinkedHashMap<>(Map.of("approved",true,"issues",List.of(),"checks",checks));
    }
    private BusinessAnswerReview reviewer(Map<String,Object> verdict) {
        return new BusinessAnswerReview(json,new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                try{return new ModelReply(json.valueToTree(Map.of("tool_calls",List.of(Map.of("function",Map.of("name","review_business_answer","arguments",json.writeValueAsString(verdict)))))),json.createObjectNode());}
                catch(Exception e){throw new IllegalStateException(e);}
            }
        });
    }
    @Test void globalApprovalCannotOverrideAFailedBusinessBoundary() {
        for(String key:CHECKS){
            var verdict=verdict();((Map<String,Object>)verdict.get("checks")).put(key,false);
            var error=assertThrows(BankException.class,()->reviewer(verdict).check(List.of(),new GroundedAnswer(json,List.of()),"候选回答"),key);
            assertEquals("ANSWER_REVIEW_CONTRACT_INVALID",error.code);
        }
    }
    @Test void missingOrNonBooleanCheckCannotBeTreatedAsApproval() {
        for(Object invalid:List.of("true",1)){
            var verdict=verdict();((Map<String,Object>)verdict.get("checks")).put("query_coverage",invalid);
            assertThrows(BankException.class,()->reviewer(verdict).check(List.of(),new GroundedAnswer(json,List.of()),"候选回答"));
        }
        var verdict=verdict();((Map<String,Object>)verdict.get("checks")).remove("risk_boundary");
        assertThrows(BankException.class,()->reviewer(verdict).check(List.of(),new GroundedAnswer(json,List.of()),"候选回答"));
    }
    @Test void validChecksAllowASpecificClarification() {
        assertDoesNotThrow(()->reviewer(verdict()).check(List.of(Map.of("role","user","content","这笔维修是谁批准的？")),new GroundedAnswer(json,List.of()),"银行没有维修审批记录，需要你确认报价及批准人。"));
    }
    @Test void rejectionWithoutAnyConcreteIssueCannotCountAsSemanticEvidence() {
        var verdict=verdict();verdict.put("approved",false);
        var rejected=assertThrows(BankException.class,()->reviewer(verdict).check(List.of(),new GroundedAnswer(json,List.of()),"候选回答"));
        assertFalse(rejected.getMessage().startsWith("业务语义需修正："),"Malformed denial must not look like a valid semantic finding");
    }
    @Test void malformedVerdictReportsOnlyFieldTypesAndArraySizes() {
        var verdict=verdict();verdict.put("approved","DO_NOT_ECHO");
        var error=assertThrows(BankException.class,()->reviewer(verdict).check(List.of(),new GroundedAnswer(json,List.of()),"PRIVATE_CANDIDATE"));
        assertEquals("ANSWER_REVIEW_CONTRACT_INVALID",error.code);
        assertTrue(error.getMessage().contains("approved=STRING"));
        assertTrue(error.getMessage().contains("issues=ARRAY(0)"));
        assertFalse(error.getMessage().contains("DO_NOT_ECHO"));
        assertFalse(error.getMessage().contains("PRIVATE_CANDIDATE"));
        var tooMany=verdict();tooMany.put("issues",List.of("a","b","c","d"));
        error=assertThrows(BankException.class,()->reviewer(tooMany).check(List.of(),new GroundedAnswer(json,List.of()),"候选"));
        assertTrue(error.getMessage().contains("issues=ARRAY(4)"));
    }
    @Test void validConcreteSemanticRejectionKeepsItsSeparateCode() {
        var verdict=verdict();verdict.put("approved",false);verdict.put("issues",List.of("无证据保证以后不收费，应说明合同状态未知。"));
        ((Map<String,Object>)verdict.get("checks")).put("revocation_boundary",false);
        var error=assertThrows(BankException.class,()->reviewer(verdict).check(List.of(),new GroundedAnswer(json,List.of()),"以后不收费。"));
        assertEquals("ANSWER_NOT_GROUNDED",error.code);
        assertTrue(error.getMessage().startsWith("业务语义需修正："));
    }
    @Test void unreadableReviewJsonDoesNotEchoRawArguments() {
        var client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                return new ModelReply(json.valueToTree(Map.of("tool_calls",List.of(Map.of("function",Map.of(
                    "name","review_business_answer","arguments","BROKEN_PRIVATE_ARGUMENTS"))))),json.createObjectNode());
            }
        };
        var error=assertThrows(BankException.class,()->new BusinessAnswerReview(json,client).check(List.of(),new GroundedAnswer(json,List.of()),"PRIVATE_CANDIDATE"));
        assertEquals("ANSWER_REVIEW_CONTRACT_INVALID",error.code);
        assertTrue(error.getMessage().contains("root=MISSING"));
        assertFalse(error.getMessage().contains("BROKEN_PRIVATE_ARGUMENTS"));
        assertFalse(error.getMessage().contains("PRIVATE_CANDIDATE"));
    }
    @Test void reviewReceivesLatestQuestionAndExplicitAvailableEvidenceAndLimits() {
        ModelClient client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                try {
                    var input=json.readTree(request.messages().get(1).get("content").toString());
                    assertEquals("只核对维修，不要复述总额",input.path("latest_customer_question").asText());
                    var context=input.path("business_context");
                    assertEquals("2026-09",context.path("available_statement_months").get(0).asText());
                    assertFalse(context.path("capabilities").path("merchant_contract_status").asBoolean(true));
                    assertFalse(context.path("capabilities").path("risk_assessment").asBoolean(true));
                    assertFalse(context.path("accounting_rules").path("refund_reduces_expense").asBoolean(true));
                    return new ModelReply(json.valueToTree(Map.of("tool_calls",List.of(Map.of("function",Map.of("name","review_business_answer","arguments",json.writeValueAsString(verdict())))))),json.createObjectNode());
                }catch(java.io.IOException e){throw new IllegalStateException(e);}
            }
        };
        var facts=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_statement",Map.of("month","2026-09","expense_minor",200000,"income_minor",10000))));
        new BusinessAnswerReview(json,client).check(List.of(Map.of("role","user","content","分析账单"),Map.of("role","assistant","content","旧回答"),Map.of("role","user","content","只核对维修，不要复述总额")),facts,"请核对维修单据。");
    }
}
