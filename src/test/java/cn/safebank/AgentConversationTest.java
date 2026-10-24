package cn.safebank;

import cn.safebank.model.*;
import static cn.safebank.model.ModelContracts.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:conversation;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class AgentConversationTest extends BankTestFixture {
    @Autowired ObjectMapper json;
    @Test void incompleteGenerationCannotDispatchToolsOrApprovePartialText() {
        for(var reason:List.of(FinishReason.LENGTH,FinishReason.CONTENT_FILTER,FinishReason.INSUFFICIENT_SYSTEM_RESOURCE,FinishReason.ABORTED,FinishReason.UNKNOWN)) {
            for(var candidate:List.of(call("get_statement",Map.of("month","2026-09")),
                    call("preview_transfer",Map.of("source_id","checking","recipient_id","r1","amount_minor",10000)),
                    answer("以上支出口径为负向非内","general"),
                    new ModelReply(json.valueToTree(Map.of("content","以上支出口径为负向非内")),json.createObjectNode()))) {
                var result=sequence(new ModelReply(candidate.message(),candidate.usage(),reason))
                    .chat("u1","分析九月账单",new MockHttpSession());
                assertEquals("SAFE_FALLBACK",result.get("answerSource"));
                assertTrue(result.get("trace").toString().contains("MODEL_RESPONSE_INCOMPLETE"));
                assertFalse(result.get("trace").toString().contains("get_statement /"));
                assertFalse(result.get("text").toString().contains("负向非内"));
                assertEquals(1,json.valueToTree(result.get("modelUsage")).path("request_count").asInt());
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
                assertEquals(1243000,bank.balance("u1","checking"));
            }
        }
    }
    @Test void incompleteReviewCannotApproveEvenAValidLookingVerdict() {
        ModelClient client=new ModelClient(){
            int calls;
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                assertTrue(++calls<=2,"Incomplete review must stop without another paid repair");
                if(reviewRequest(request)){var r=review(true,List.of());return new ModelReply(r.message(),r.usage(),FinishReason.LENGTH);}
                return answer("请提供维修报价及凭证以便核对。","general");
            }
        };
        var result=new AgentService(gateway,json,client).chat("u1","核对维修需要什么资料？",new MockHttpSession());
        assertEquals("SAFE_FALLBACK",result.get("answerSource"));
        assertTrue(result.get("trace").toString().contains("ANSWER_REVIEW_CONTRACT_INVALID"));
        assertFalse(result.get("trace").toString().contains("business_answer_review / PASS"));
        assertEquals(2,json.valueToTree(result.get("modelUsage")).path("request_count").asInt());
    }
    @Test void normallyStoppedButUnfinishedTextCanBeRepairedAfterConcreteReviewRejection() {
        ModelClient client=new ModelClient(){
            int next;
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                return switch(next++){
                    case 0 -> new ModelReply(json.valueToTree(Map.of("content","以上支出口径为负向非内")),null,FinishReason.STOP);
                    case 1 -> call("review_business_answer",Map.of("approved",false,"issues",List.of("原句‘负向非内’在词语中途结束，需完整说明。"),
                        "checks",Map.of("revocation_boundary",true,"accounting_boundary",true,"risk_boundary",true,"query_coverage",true,"question_focus",false)));
                    case 2 -> answer("请提供维修报价及凭证以便核对。","general");
                    case 3 -> review(true,List.of());
                    default -> throw new AssertionError("Unexpected extra request");
                };
            }
        };
        var result=new AgentService(gateway,json,client).chat("u1","核对维修需要什么资料？",new MockHttpSession());
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
        assertEquals("请提供维修报价及凭证以便核对。",result.get("text"));
        assertTrue(result.get("trace").toString().contains("ANSWER_NOT_GROUNDED"));
        assertEquals(4,json.valueToTree(result.get("modelUsage")).path("request_count").asInt());
    }
    ModelReply call(String name,Object args) {
        try { return new ModelReply(json.valueToTree(Map.of("role","assistant","tool_calls",List.of(Map.of("id",UUID.randomUUID().toString(),"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(args)))))),json.createObjectNode()); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    ModelReply answer(String text,String kind) { return call("answer_customer",Map.of("answer",text,"kind",kind)); }
    ModelReply review(boolean approved,List<String> issues) {
        return call("review_business_answer",Map.of("approved",approved,"issues",issues,"checks",Map.of(
            "revocation_boundary",true,"accounting_boundary",true,"risk_boundary",true,"query_coverage",approved,"question_focus",true)));
    }
    boolean reviewRequest(ModelRequest request) { return json.valueToTree(request.tools()).path(0).path("function").path("name").asText().equals("review_business_answer"); }
    @Test void statementHistoryUsesFactCatalogWhileApiKeepsCompleteBankData() {
        var session=new MockHttpSession();
        ModelClient client=new ModelClient() {
            int next;
            public String mode(){return "SCRIPTED_TEST";} public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request) {
                if(reviewRequest(request))return review(true,List.of());
                if(next++==0)return call("get_statement",Map.of("month","2026-09"));
                var receipt=json.valueToTree(request.messages()).findValuesAsText("content").stream()
                    .filter(s->s.contains("AVAILABLE_IN_FACT_CATALOG")).findFirst();
                assertTrue(receipt.isPresent(),"Expected compact query receipt");
                assertTrue(receipt.get().contains("statement_2026_09"));
                assertFalse(receipt.get().contains("transactions"));
                assertTrue(request.messages().toString().contains("statement_2026_09.expense_minor"));
                return answer("九月支出{{statement_2026_09.expense_minor}}。","analysis");
            }
        };
        var result=new AgentService(gateway,json,client).chat("u1","分析9月账单",session);
        assertTrue(json.valueToTree(result.get("data")).has("transactions"),result.toString());
    }
    AgentService sequence(ModelReply... steps) {
        ModelClient client=new ModelClient() {
            int next;
            public String mode(){return "SCRIPTED_TEST";} public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){if(reviewRequest(request))return review(true,List.of());assertTrue(next<steps.length,"Unexpected extra model call");return steps[next++];}
        };
        return new AgentService(gateway,json,client);
    }
    @Test void modelCanExpandOmittedTransactionsWithoutLosingApiEvidence() {
        ModelClient client=new ModelClient() {
            int next;
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request) {
                if(reviewRequest(request))return review(true,List.of());
                switch(next++) {
                    case 0:return call("get_statement",Map.of("month","2026-09"));
                    case 1:
                        assertFalse(request.messages().toString().contains("statement_2026_09.transactions.0.id"));
                        return call("get_statement",Map.of("month","2026-09","include_transactions",true));
                    default:
                        assertTrue(request.messages().toString().contains("statement_2026_09.transactions.0.id"));
                        return answer("本月支出{{statement_2026_09.expense_minor}}。","analysis");
                }
            }
        };
        var result=new AgentService(gateway,json,client).chat("u1","九月账单总览",new MockHttpSession());
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
        assertTrue(json.valueToTree(result.get("data")).path("transactions").size()>0);
    }
    @Test void unsupportedRefundLinkIsRepairedBeforeSemanticReviewEvenIfReviewerWouldApprove() {
        db.update("INSERT INTO transactions VALUES('unrelated-refund','u1','checking','家电退款','退款',320000,DATE '2026-09-15')");
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),
            answer("这笔退款对应上月那笔消费。","general"),
            answer("记录中有退款入账，不能确认对应原订单，请核对订单凭证。","general"));
        var result=agent.chat("u1","退款对应哪笔购物？",new MockHttpSession());
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
        assertEquals("记录中有退款入账，不能确认对应原订单，请核对订单凭证。",result.get("text"));
        assertTrue(result.get("trace").toString().contains("缺少原订单"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void inlineMonthAndAmountReachReviewWithoutAnUnnecessaryRepairCall() {
        String text="9月3,403.00元支出，大额候选需要结合用途核对。";
        for(ModelReply candidate:List.of(answer(text,"analysis"),new ModelReply(json.valueToTree(Map.of("role","assistant","content",text)),json.createObjectNode()))) {
            var result=sequence(call("get_statement",Map.of("month","2026-09")),candidate)
                .chat("u1","简短分析9月账单",new MockHttpSession());
            assertEquals("MODEL_GROUNDED",result.get("answerSource"));
            assertEquals(text,result.get("text"));
            assertFalse(result.get("trace").toString().contains("ANSWER_NOT_GROUNDED"));
            assertTrue(result.get("trace").toString().contains("business_answer_review / PASS"));
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void structuralBlocksReachReviewWithoutAnUnnecessaryRepairCall() {
        String text="这两块内容分别说明。账单支出{{statement_2026_09.expense_minor}}。";
        for(ModelReply candidate:List.of(answer(text,"analysis"),new ModelReply(json.valueToTree(Map.of("role","assistant","content",text)),json.createObjectNode()))) {
            var agent=sequence(call("get_statement",Map.of("month","2026-09")),candidate);
            var result=agent.chat("u1","分析9月账单",new MockHttpSession());
            assertEquals("MODEL_GROUNDED",result.get("answerSource"));
            assertTrue(result.get("text").toString().startsWith("这两块内容分别说明。"));
            assertFalse(result.get("trace").toString().contains("ANSWER_NOT_GROUNDED"));
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void unsupportedMoneyReceivesGrammarFeedbackAndIsRepairedWithoutExecution() {
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),
            answer("这两块钱可以节省。账单支出{{statement_2026_09.expense_minor}}。","analysis"),
            answer("账单支出{{statement_2026_09.expense_minor}}，节省目标还需确认。","analysis"));
        var result=agent.chat("u1","分析9月账单",new MockHttpSession());
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
        assertTrue(result.get("trace").toString().contains("后接：钱"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void sameStatementSupportsSummaryThenFocusedFollowupWithoutRepeatingTemplate() {
        var session=new MockHttpSession();
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),
            answer("本月支出为{{e0.expense_minor}}，主要集中在居住。", "analysis"),
            answer("先核对这笔大额交易：{{e0.anomaly_candidates.0.booked_on}}，{{e0.anomaly_candidates.0.merchant}}，{{e0.anomaly_candidates.0.spending_minor}}。你是否认可这笔扣费？", "analysis"));
        var first=agent.chat("u1","分析2026-09账单",session);
        var second=agent.chat("u1","核对大额支出",session);
        String focused=second.get("text").toString();
        assertNotEquals(first.get("text"),focused);assertTrue(focused.contains("先核对这笔大额交易"),focused);
        assertTrue(focused.contains("2,500.00"),focused);assertTrue(focused.contains("2026-09-01"),focused);
        assertEquals("MODEL_GROUNDED",second.get("answerSource"));
        assertEquals(1243000,bank.balance("u1","checking"));
    }
    @Test void modelMayOfferContextualAdviceAndAskAClarifyingQuestion() {
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),
            answer("居住支出为{{e0.categories.居住}}。账单本身不能说明租约能否调整；如果短期不能改变住房支出，可以先核对可选消费。你更希望降低固定开支，还是减少日常消费？", "analysis"));
        String text=agent.chat("u1","哪些钱可以省？",new MockHttpSession()).get("text").toString();
        assertTrue(text.contains("租约能否调整"),text);assertTrue(text.contains("日常消费"),text);assertTrue(text.contains("2,500.00"),text);
    }
    @Test void missingInformationUsesModelsSpecificClarificationInsteadOfGenericNotice() {
        var agent=sequence(answer("你想比较哪两个月的账单？也可以先告诉我你最近最想控制的支出类别。", "clarification"));
        String text=agent.chat("u1","帮我看看有没有花多",new MockHttpSession()).get("text").toString();
        assertEquals("你想比较哪两个月的账单？也可以先告诉我你最近最想控制的支出类别。",text);
    }
    @Test void normalModelProseMayCopyVerifiedAmountsAndDatesWithoutSpecialOutputSyntax() {
        var direct=new ModelReply(json.valueToTree(Map.of("role","assistant","content",
            "九月支出3,403.00元。先核对2026-09-01的租金2,500.00元；你认可这笔扣费吗？")),json.createObjectNode());
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),direct);
        var result=agent.chat("u1","帮我看看9月大额支出",new MockHttpSession());
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));assertTrue(result.get("text").toString().contains("你认可这笔扣费吗"));
    }
    @Test void plainClarificationDoesNotRequireAToolCall() {
        var direct=new ModelReply(json.valueToTree(Map.of("role","assistant","content","你希望比较哪两个月，还是只检查某一类消费？")),json.createObjectNode());
        var result=sequence(direct).chat("u1","帮我看看有没有花多",new MockHttpSession());
        assertEquals("你希望比较哪两个月，还是只检查某一类消费？",result.get("text"));
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
    }
    @Test void unsupportedPlainAnswerCanRefreshMissingEvidenceRatherThanImmediatelyFallback() {
        var direct=new ModelReply(json.valueToTree(Map.of("role","assistant","content","季度收费180元，不是每月收费。")),json.createObjectNode());
        var agent=sequence(direct,call("list_mandates",Map.of()),answer("当前明确月度授权合计{{mandates_summary.known_monthly_total_minor}}。", "analysis"));
        var result=agent.chat("u1","季度的是按月扣吗？",new MockHttpSession());
        assertEquals("ACTION_RECEIPT",result.get("answerSource"));
        assertFalse(((java.util.List<?>)result.get("actions")).isEmpty());assertFalse(result.get("text").toString().contains("83.00"));
    }
    @Test void unsupportedNumberIsRejectedAndModelCanRepairUsingBankEvidence() {
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),
            answer("你本月支出999元。", "analysis"),answer("重新核对后，本月支出是{{e0.expense_minor}}。", "analysis"));
        var result=agent.chat("u1","本月支出多少？",new MockHttpSession());
        assertTrue(result.get("text").toString().contains("重新核对后"));assertFalse(result.get("text").toString().contains("999"));
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));
    }
    @Test void calculatedComparisonComesFromExistingFacts() {
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),call("get_statement",Map.of("month","2026-08")),
            call("calculate_business_metric",Map.of("operation","DIFFERENCE","facts",List.of("e0.expense_minor","e1.expense_minor"))),
            answer("两个月的支出差额为{{calc0}}。先看差额，再核对具体消费变化。", "analysis"));
        var result=agent.chat("u1","比较九月和八月支出",new MockHttpSession());
        assertTrue(result.get("text").toString().contains("174.90"),result.toString());assertEquals("MODEL_GROUNDED",result.get("answerSource"));
    }
    @Test void inventedExecutionCannotEnterNaturalLanguageAnswer() {
        var agent=sequence(call("get_accounts",Map.of()),answer("转账成功并到账，余额为{{e0.0.balance_minor}}。", "analysis"),
            answer("目前账户余额为{{e0.0.balance_minor}}，这次只查询账户。", "analysis"));
        var result=agent.chat("u1","查询余额",new MockHttpSession());
        assertFalse(result.get("text").toString().contains("转账成功"));assertTrue(result.get("text").toString().contains("只查询"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void factsDoNotLeakWhenSameSessionChangesUser() {
        var agent=sequence(call("get_statement",Map.of("month","2026-09")),answer("本月支出{{e0.expense_minor}}。", "analysis"),
            answer("旧用户的支出{{e0.expense_minor}}。", "analysis"),answer("请告诉我要查看哪个月份。", "clarification"));
        var session=new MockHttpSession();agent.chat("u1","查九月账单",session);
        String text=agent.chat("u2","那笔大额支出呢",session).get("text").toString();
        assertEquals("请告诉我要查看哪个月份。",text);assertFalse(text.contains("3,403.00"));
    }
    @Test void semanticReviewerCanRejectAnUnsupportedInferenceAndRequestRepair() {
        int[] generation={0},reviews={0};
        ModelClient client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                if(reviewRequest(request)){
                    assertTrue(request.messages().toString().contains("statement_2026_09"));
                    boolean approved=++reviews[0]>1;
                    return review(approved,approved?List.of():List.of("银行账单不能证明房租合理，请询问租约而非断言"));
                }
                return switch(generation[0]++){case 0->call("get_statement",Map.of("month","2026-09"));
                    case 1->answer("这笔房租{{e0.categories.居住}}一定合理。","analysis");
                    default->answer("房租{{e0.categories.居住}}需你确认是否符合租约。","analysis");};
            }
        };
        var result=new AgentService(gateway,json,client).chat("u1","这笔房租正常吗？",new MockHttpSession());
        assertEquals(2,reviews[0]);assertTrue(result.get("text").toString().contains("需你确认"));assertFalse(result.get("text").toString().contains("一定合理"));
    }
    @Test void malformedOrUnauthorizedReviewCannotApproveAnswerOrMoveMoney() {
        for(boolean unauthorized:List.of(true,false)){
            int[] next={0},reviews={0};
            ModelClient client=new ModelClient(){
                public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
                public ModelReply complete(ModelRequest request){
                    if(reviewRequest(request)){reviews[0]++;return unauthorized?call("authorize",Map.of()):new ModelReply(json.valueToTree(Map.of("content","approved")),json.createObjectNode());}
                    return next[0]++==0?call("get_statement",Map.of("month","2026-09")):answer("支出{{e0.expense_minor}}。","analysis");
                }
            };
            var result=new AgentService(gateway,json,client).chat("u1","看账单",new MockHttpSession());
            assertTrue(reviews[0]>0);assertEquals("SAFE_FALLBACK",result.get("answerSource"));assertEquals(1243000,bank.balance("u1","checking"));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
        }
    }
    @Test void mandateFollowupRequiresNewQueryConfirmationInsteadOfSilentRefresh() {
        var agent=sequence(call("list_mandates",Map.of()),call("list_mandates",Map.of()));
        var session=new MockHttpSession();var first=agent.chat("u1","查看代扣",session);
        db.update("UPDATE mandates SET status='REVOKED' WHERE id='music'");
        var result=agent.chat("u1","现在月付合计呢？",session);
        assertEquals("ACTION_RECEIPT",result.get("answerSource"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM action_authorizations",Integer.class));
        assertFalse(result.get("text").toString().contains("68.00"));
        assertTrue(((List<?>)result.get("contextResults")).isEmpty());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM actions WHERE kind='QUERY_MANDATES'",Integer.class));
    }
    @Test void failedCoverageMakesModelFetchOnlyMissingMonthAndKeepTheFollowup() {
        int[] generated={0},reviewed={0};
        ModelClient client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                if(reviewRequest(request)){
                    if(++reviewed[0]==2)return call("review_business_answer",Map.of("approved",false,"issues",List.of("缺少上月证据，请调用get_statement查询2026-08再比较"),"checks",Map.of(
                        "revocation_boundary",true,"accounting_boundary",true,"risk_boundary",true,"query_coverage",false,"question_focus",true)));
                    return review(true,List.of());
                }
                return switch(generated[0]++){
                    case 0->call("get_statement",Map.of("month","2026-09"));
                    case 1->answer("九月支出{{statement_2026_09.expense_minor}}。","analysis");
                    case 2->answer("本月支出{{statement_2026_09.expense_minor}}，与上月相比更多。","analysis");
                    case 3->{assertTrue(request.messages().toString().contains("查询2026-08"));yield call("get_statement",Map.of("month","2026-08"));}
                    default->answer("九月{{statement_2026_09.expense_minor}}，八月{{statement_2026_08.expense_minor}}，九月支出更多。","analysis");
                };
            }
        };
        var agent=new AgentService(gateway,json,client);var session=new MockHttpSession();
        agent.chat("u1","看2026-09支出",session);
        var result=agent.chat("u1","再和上月比较",session);
        assertEquals("MODEL_GROUNDED",result.get("answerSource"));assertTrue(result.get("text").toString().contains("3,228.10"));
        var results=(List<BankReplyComposer.Evidence>)result.get("results");
        assertEquals(1,results.size());assertEquals("2026-08",((Map<?,?>)results.getFirst().data()).get("month"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void requestUsageIncludesBothGenerationAndReviewInsteadOfOneCustomerTurn() {
        int[] requests={0};
        ModelClient client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                ModelReply reply=reviewRequest(request)?review(true,List.of()):requests[0]==0?
                    call("get_statement",Map.of("month","2026-09")):answer("九月支出{{statement_2026_09.expense_minor}}。","analysis");
                requests[0]++;
                return new ModelReply(reply.message(),json.valueToTree(Map.of("prompt_tokens",10,"completion_tokens",3,"total_tokens",13,
                    "prompt_cache_hit_tokens",6,"prompt_cache_miss_tokens",4)));
            }
        };
        var result=new AgentService(gateway,json,client).chat("u1","看九月支出",new MockHttpSession());
        var usage=json.valueToTree(result.get("modelUsage"));
        assertEquals(3,usage.path("request_count").asInt());
        assertEquals(2,usage.path("generation_requests").asInt());assertEquals(1,usage.path("review_requests").asInt());
        assertEquals(30,usage.path("prompt_tokens").asLong());assertEquals(9,usage.path("completion_tokens").asLong());
        assertEquals(39,usage.path("total_tokens").asLong());assertTrue(usage.path("usage_complete").asBoolean());
    }
    @Test void lowerRequestBudgetStopsBeforeCallingReviewerAndResetsOnNextTurn() {
        int[] calls={0},generation={0};
        ModelClient client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){
                calls[0]++;if(reviewRequest(request))return review(true,List.of());
                return generation[0]++%2==0?call("get_statement",Map.of("month","2026-09")):
                    answer("支出{{statement_2026_09.expense_minor}}。","analysis");
            }
        };
        var agent=new AgentService(gateway,json,client);var session=new MockHttpSession();
        var limited=agent.chat("u1","查询支出",session,2);
        assertEquals(1,calls[0]);assertEquals("SAFE_FALLBACK",limited.get("answerSource"));
        var firstUsage=json.valueToTree(limited.get("modelUsage"));
        assertEquals(1,firstUsage.path("request_count").asInt());assertFalse(firstUsage.path("usage_complete").asBoolean());
        assertTrue(firstUsage.path("total_tokens").isNull(),"Missing provider usage must not appear as zero cost");
        var next=agent.chat("u1","再查一次",session,3);
        assertEquals(3,calls[0]);assertEquals("MODEL_GROUNDED",next.get("answerSource"));
        assertEquals(2,json.valueToTree(next.get("modelUsage")).path("request_count").asInt());
        assertThrows(BankException.class,()->agent.chat("u1","不合法上限",session,13));assertEquals(3,calls[0]);
        assertEquals(1243000,bank.balance("u1","checking"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void providerFailureDuringReviewStopsWithoutGeneratingAnotherCandidate() {
        for(boolean plain:List.of(true,false))for(String code:List.of("MODEL_API_ERROR","MODEL_UNAVAILABLE","MODEL_INTERRUPTED")){
            int[] calls={0},generation={0};
            ModelClient client=new ModelClient(){
                public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
                public ModelReply complete(ModelRequest request){
                    calls[0]++;if(reviewRequest(request))throw new BankException(code,"provider failed");
                    if(generation[0]++==0)return call("get_statement",Map.of("month","2026-09"));
                    return plain?new ModelReply(json.valueToTree(Map.of("role","assistant","content","九月支出3,403.00元。")),json.createObjectNode()):
                        answer("九月支出{{statement_2026_09.expense_minor}}。","analysis");
                }
            };
            var failure=assertThrows(BankException.class,()->new AgentService(gateway,json,client).chat("u1","九月支出呢？",new MockHttpSession()));
            assertEquals(code,failure.code);assertEquals(3,calls[0]);
        }
        assertEquals(1243000,bank.balance("u1","checking"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void oneRequestBudgetDoesNotGenerateAnUnreviewableAnswer() {
        var client=new ModelClient(){
            public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
            public ModelReply complete(ModelRequest request){fail("Must reserve review budget before generation");return null;}
        };
        var result=new AgentService(gateway,json,client).chat("u1","查询账单",new MockHttpSession(),1);
        assertEquals("SAFE_FALLBACK",result.get("answerSource"));
        assertEquals(0,json.valueToTree(result.get("modelUsage")).path("request_count").asInt());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
    @Test void malformedReviewStopsAndKeepsToolHistoryValidForNextTurn() {
        for(boolean plain:List.of(true,false)) {
            int[] requests={0},generation={0},reviews={0};
            var client=new ModelClient(){
                public String mode(){return "SCRIPTED_TEST";}public String modelName(){return "scripted";}
                public ModelReply complete(ModelRequest request){
                    requests[0]++;
                    var messages=request.messages();
                    for(int i=0;i<messages.size()-1;i++)if(messages.get(i).containsKey("tool_calls"))
                        assertEquals("tool",messages.get(i+1).get("role"),"Saved tool calls must have their responses");
                    if(reviewRequest(request)) {
                        if(reviews[0]++>0)return review(true,List.of());
                        return call("review_business_answer",Map.of("approved","PRIVATE_MARKER","issues",List.of(),"checks",Map.of()));
                    }
                    if(generation[0]++==0)return call("get_statement",Map.of("month","2026-09"));
                    String text="账单支出{{statement_2026_09.expense_minor}}。";
                    return plain?new ModelReply(json.valueToTree(Map.of("role","assistant","content",text)),json.createObjectNode()):answer(text,"analysis");
                }
            };
            var session=new MockHttpSession();var agent=new AgentService(gateway,json,client);
            var failed=agent.chat("u1","分析9月账单",session);
            assertEquals(3,requests[0]);assertEquals("SAFE_FALLBACK",failed.get("answerSource"));
            assertTrue(failed.get("trace").toString().contains("ANSWER_REVIEW_CONTRACT_INVALID"));
            assertFalse(failed.get("trace").toString().contains("PRIVATE_MARKER"));
            assertEquals("MODEL_GROUNDED",agent.chat("u1","只说支出",session).get("answerSource"));
            assertEquals(5,requests[0]);
        }
        assertEquals(1243000,bank.balance("u1","checking"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM actions",Integer.class));
    }
}
