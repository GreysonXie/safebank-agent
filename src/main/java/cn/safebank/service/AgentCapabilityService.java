package cn.safebank.service;
import cn.safebank.*;
import cn.safebank.dao.*;
import cn.safebank.dto.AgentToolRequestDTO;
import cn.safebank.entity.*;
import cn.safebank.vo.AgentToolReceiptVO;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static cn.safebank.service.ContextEvidenceService.*;
@Service @Profile("agent-gateway")
public class AgentCapabilityService {
    public static final Set<String> SCOPES=Set.of("statement","statement_compare","recurring_analysis","account_query","mandate_receipt","transfer_preview","revoke_preview","savings_task_prepare","savings_task_status");
    @org.springframework.beans.factory.annotation.Autowired private SavingsTaskPlanService savingsTasks;
    @org.springframework.beans.factory.annotation.Autowired private SavingsTaskPlanValidator taskValidator;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowService workflows;
    @org.springframework.beans.factory.annotation.Autowired private AgentPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired private RiskUserLockService riskLocks;
    private final AgentGatewayMapper mapper;private final AgentConversationMapper conversations;private final GatewayTurnService turns;
    private final AccountService accounts;private final StatementService statements;private final ObjectMapper json;private final TransactionTemplate tx;
    private final AgentMandateReceiptService mandateReceipts;private final SecureRandom random=new SecureRandom();
    public AgentCapabilityService(AgentGatewayMapper m,AgentConversationMapper c,GatewayTurnService t,AccountService a,StatementService s,ObjectMapper j,PlatformTransactionManager manager,AgentMandateReceiptService mandateReceipts){this.mandateReceipts=mandateReceipts;mapper=m;conversations=c;turns=t;accounts=a;statements=s;json=j;tx=new TransactionTemplate(manager);}
    public String issue(String turnId){return tx.execute(status->{
        var t=turns.lock(turnId,true);
        if("PAUSED".equals(t.getState()))throw new BankException("AGENT_REVIEW_REQUIRED","原轮次已暂停，不能签发或续签工具权限");
        var c=conversations.selectOwned(t.getUserId(),t.getConversationId());
        var expiry=Collections.min(List.of(turns.now().plusMinutes(5),c.getExpiresAt(),t.getSessionExpiresAt()));
        mapper.revoke(t.getUserId(),turnId);byte[] secret=new byte[32];random.nextBytes(secret);String token=HexFormat.of().formatHex(secret);
        var g=new AgentServiceGrantEntity();g.setId(UUID.randomUUID().toString());g.setTokenHash(hash(token));g.setUserId(t.getUserId());g.setConversationId(t.getConversationId());g.setTurnId(turnId);g.setRequestId(t.getRequestId());g.setScopeJson(encode(SCOPES.stream().sorted().toList()));g.setState("ACTIVE");g.setCallLimit(8);g.setCallsUsed(0);g.setCreatedAt(turns.now());g.setExpiresAt(expiry);require(mapper.insertGrant(g));return token;
    });}
    public String renew(String turnId,String previousToken){if(previousToken==null||!previousToken.matches("[a-f0-9]{64}"))throw GatewayTurnService.denied();var g=mapper.grant(hash(previousToken));if(g==null||!g.getTurnId().equals(turnId))throw GatewayTurnService.denied();return issue(turnId);}
    public AgentToolReceiptVO call(String token,AgentToolRequestDTO request){
        if(token==null||!token.matches("[a-f0-9]{64}"))throw GatewayTurnService.denied();
        var identity=mapper.grant(hash(token));if(identity==null)throw GatewayTurnService.denied();
        return riskLocks.withUserLock(identity.getUserId(),()->tx.execute(status->{
        var g=authorize(token,true);checkInput(request);
        var old=mapper.receipt(g.getTurnId(),request.operationKey());
        if(old!=null){if(!old.getInputHash().equals(request.inputHash())||!old.getToolName().equals(request.tool()))throw new BankException("CONFLICT","相同工具操作编号的输入不同");return view(old,true);}
        if(mapper.receipts(g.getTurnId()).size()>=8)throw new BankException("TOOL_QUOTA_EXCEEDED","本轮只读工具额度已用完");
        if(Set.of("savings_task_prepare","transfer_preview","revoke_preview").contains(request.tool())&&mapper.receipts(g.getTurnId()).stream().anyMatch(r->Set.of("savings_task_prepare","transfer_preview","revoke_preview").contains(r.getToolName())))throw new BankException("PREVIEW_LIMIT","每轮最多准备一个操作或任务");
        Object data=switch(request.tool()){
            case "savings_task_prepare"->savingsTasks.prepare(g.getUserId(),g.getConversationId(),g.getTurnId(),request.operationKey(),taskValidator.validate(request.args().get("taskPlan")));
            case "savings_task_status"->workflows.get(g.getUserId(),UUID.fromString(request.args().path("workflowId").asText()));
            case "transfer_preview","revoke_preview"->previews.prepare(g.getUserId(),g.getConversationId(),g.getTurnId(),request.operationKey(),request.tool(),request.args());
            case "mandate_receipt"->mandateReceipts.read(g.getUserId(),g.getConversationId());case "account_query"->accounts.list(g.getUserId());
            case "statement"->statements.statement(g.getUserId(),month(request.args(),"month"));
            case "statement_compare"->Map.of("primary",statements.statement(g.getUserId(),month(request.args(),"month")),"comparison",statements.statement(g.getUserId(),month(request.args(),"comparisonMonth")));
            case "recurring_analysis"->statements.recurring(g.getUserId());
            default->throw GatewayTurnService.denied();
        };
        var r=new AgentToolCallReceiptEntity();r.setId(UUID.randomUUID().toString());r.setTurnId(g.getTurnId());r.setConversationId(g.getConversationId());r.setUserId(g.getUserId());r.setOperationKey(request.operationKey());r.setInputHash(request.inputHash());r.setToolName(request.tool());r.setGrantId(g.getId());r.setResultJson(encode(data));r.setResultHash(hash(r.getResultJson()));r.setObservedAt(request.tool().equals("mandate_receipt")&&data instanceof Map<?,?> values&&values.containsKey("queriedAt")?Instant.parse(values.get("queriedAt").toString()).atOffset(ZoneOffset.UTC):turns.now());require(mapper.useGrant(g.getId()));require(mapper.insertReceipt(r));return view(r,false);
    }));}
    /** Identity comes solely from persisted grant, never callback userId. */
    public AgentServiceGrantEntity authorize(String token,boolean live){
        if(token==null||!token.matches("[a-f0-9]{64}"))throw GatewayTurnService.denied();
        var g=mapper.grant(hash(token));if(g==null)throw GatewayTurnService.denied();turns.lock(g.getTurnId(),live);g=mapper.grantCurrent(hash(token));
        if(!g.getState().equals("ACTIVE")||!g.getExpiresAt().toInstant().isAfter(turns.now().toInstant())||!new HashSet<>(json.convertValue(tree(g.getScopeJson()),List.class)).equals(SCOPES))throw GatewayTurnService.denied();
        return g;
    }
    /** For bound usage reporting only. Never authorizes tools or a new reservation. */
    public AgentServiceGrantEntity originalIdentity(String token){if(token==null||!token.matches("[a-f0-9]{64}"))throw GatewayTurnService.denied();var g=mapper.grant(hash(token));if(g==null)throw GatewayTurnService.denied();turns.lock(g.getTurnId(),false);return g;}
    public List<BankReplyComposer.Evidence> evidence(List<AgentToolCallReceiptEntity> receipts){
        var items=new ArrayList<BankReplyComposer.Evidence>();for(var r:receipts){var data=tree(r.getResultJson());
            switch(r.getToolName()){
                case "savings_task_prepare","savings_task_status"->{if(!data.has("code"))items.add(new BankReplyComposer.Evidence(r.getToolName().equals("savings_task_prepare")?"agent_savings_task":"agent_savings_task_status",SavingsTaskFactProjector.project(data,r.getToolName().equals("savings_task_prepare")),Map.of("queriedAt",r.getObservedAt()==null?"未提供":r.getObservedAt().toInstant().toString())));}
                case "transfer_preview","revoke_preview"->{if(r.getToolName().equals("transfer_preview")&&data.path("code").asText().equals("RECIPIENT_CLARIFICATION_REQUIRED")&&data.path("candidates").isArray())items.add(new BankReplyComposer.Evidence("resolve_recipient",json.convertValue(data.path("candidates"),Object.class)));if(!data.has("code"))items.add(new BankReplyComposer.Evidence(r.getToolName().equals("transfer_preview")?"agent_transfer_preview":"agent_revoke_preview",previews.facts(r.getUserId(),data)));}
                case "mandate_receipt"->{if(!data.has("code")){mandateReceipts.requireValidSnapshot(r.getUserId(),r.getConversationId(),data);items.add(new BankReplyComposer.Evidence("list_mandates",json.convertValue(data.get("mandates"),Object.class),Map.of("queriedAt",data.path("queriedAt").asText(),"expiresAt",data.path("expiresAt").asText())));}}case "statement"->items.add(bankEvidence("get_statement",data));
                case "statement_compare"->{items.add(bankEvidence("get_statement",data.get("primary")));items.add(bankEvidence("get_statement",data.get("comparison")));}
                case "account_query"->items.add(bankEvidence("get_accounts",data));
                case "recurring_analysis"->items.add(bankEvidence("detect_recurring_payments",data));
                default->throw GatewayTurnService.denied();
            }
        }return items;
    }
    private BankReplyComposer.Evidence bankEvidence(String name,JsonNode data){return new BankReplyComposer.Evidence(name,json.convertValue(data,Object.class));}
    private AgentToolReceiptVO view(AgentToolCallReceiptEntity r,boolean replay){
        if(!hash(r.getResultJson()).equals(r.getResultHash()))throw new BankException("CONFLICT","原工具快照完整性校验失败");
        var stored=tree(r.getResultJson());boolean mandate=r.getToolName().equals("mandate_receipt");boolean unavailable=mandate&&stored.has("code");
        var facts=unavailable?Map.<String,GroundedAnswer.Fact>of():new GroundedAnswer(json,evidence(List.of(r))).factCatalog();
        return new AgentToolReceiptVO(r.getId(),r.getOperationKey(),r.getResultHash(),r.getObservedAt().toInstant(),r.getToolName(),mandate&&!unavailable?stored.get("mandates"):stored,facts,replay,mandate&&!unavailable?Instant.parse(stored.path("queriedAt").asText()):null,mandate&&!unavailable?Instant.parse(stored.path("expiresAt").asText()):null);
    }
    public String inputHash(String tool,JsonNode args){return hash(tool+"\n"+canonical(args).toString());}
    private JsonNode canonical(JsonNode value){if(value.isObject()){var n=json.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);for(var name:names)n.set(name,canonical(value.get(name)));return n;}if(value.isArray()){var n=json.createArrayNode();value.forEach(v->n.add(canonical(v)));return n;}return value;}
    private void checkInput(AgentToolRequestDTO r){
        if(r==null||r.operationKey()==null||!r.operationKey().matches("[a-f0-9]{64}")||r.inputHash()==null||!r.inputHash().matches("[a-f0-9]{64}")||!SCOPES.contains(r.tool())||r.args()==null||!r.args().isObject()||r.args().toString().length()>8192)throw GatewayTurnService.denied();
        var keys=new HashSet<String>();r.args().fieldNames().forEachRemaining(keys::add);var expected=switch(r.tool()){case "statement"->Set.of("month");case "statement_compare"->Set.of("month","comparisonMonth");case "transfer_preview"->Set.of("sourceQuery","recipientQuery","amountMinor");case "revoke_preview"->Set.of("merchantQuery");case "savings_task_prepare"->Set.of("taskPlan");case "savings_task_status"->Set.of("workflowId");default->Set.<String>of();};
        if(!keys.equals(expected)||!inputHash(r.tool(),r.args()).equals(r.inputHash()))throw new BankException("VALIDATION_ERROR","工具参数或输入摘要无效");
        if(r.tool().equals("savings_task_status")&&(!r.args().path("workflowId").isTextual()||!r.args().path("workflowId").asText().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))throw new BankException("VALIDATION_ERROR","任务编号无效");
        if(AgentPreviewService.isPreview(r.tool()))for(var key:expected){var value=r.args().get(key);if(key.equals("amountMinor")){if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<=0)throw new BankException("VALIDATION_ERROR","金额须为正整数分");}else if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>100)throw new BankException("VALIDATION_ERROR","请提供明确的业务对象");}
    }
    private String month(JsonNode args,String name){String m=args.path(name).asText();if(!m.matches("20[0-9]{2}-(0[1-9]|1[0-2])"))throw new BankException("VALIDATION_ERROR","月份格式或范围无效");return m;}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode tree(String s){try{return json.readTree(s);}catch(Exception e){throw new IllegalStateException(e);}}
}
