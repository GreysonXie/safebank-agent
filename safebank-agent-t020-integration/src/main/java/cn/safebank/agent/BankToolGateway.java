package cn.safebank.agent;
import cn.safebank.BankException;
import cn.safebank.dto.*;
import cn.safebank.service.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;
/** Converts typed bank results only at the dynamic model protocol boundary. */
@Component public class BankToolGateway {
    private final AccountService accounts;
    private final RecipientService recipients;
    private final MandateService mandates;
    private final StatementService statements;
    private final ActionService actions;
    private final ActionExecutionService execution;
    private final ScheduleService schedules;
    private final WorkflowService workflows;
    private final ObjectMapper json;private final RiskQueryService queries;
    public BankToolGateway(AccountService accounts,RecipientService recipients,MandateService mandates,StatementService statements,ActionService actions,ActionExecutionService execution,ScheduleService schedules,WorkflowService workflows,ObjectMapper json,RiskQueryService queries) {
        this.accounts=accounts;
        this.recipients=recipients;
        this.mandates=mandates;
        this.statements=statements;
        this.actions=actions;
        this.execution=execution;
        this.schedules=schedules;
        this.workflows=workflows;
        this.json=json;this.queries=queries;
    }
    public Object execute(String user,String name,JsonNode a) {
        if(name.equals("start_savings_workflow"))return workflows.create(user,workflowRequest(a));
        if(name.equals("get_workflow"))return workflows.get(user,UUID.fromString(str(a,"workflow_id")));
        Object result=switch(name) {
            case "get_accounts" -> accounts.list(user);
            case "get_statement" -> statements.statement(user,str(a,"month"));
            case "list_mandates" -> Map.of("pendingAction",queries.prepareMandates(user,cn.safebank.common.RiskTypes.RiskChannel.AGENT),"code","QUERY_CONFIRMATION_REQUIRED","message","请确认读取本人银行侧代扣列表");
            case "detect_recurring_payments" -> statements.recurring(user);
            case "resolve_recipient" -> recipients.search(user,str(a,"query"));
            case "get_action_status" -> execution.observe(user,str(a,"action_id"));
            case "list_transfer_schedules" -> schedules.list(user);
            case "preview_transfer" -> actions.previewTransfer(user,new TransferPreviewRequestDTO(str(a,"source_id"),str(a,"recipient_id"),amount(a)));
            case "preview_revoke" -> actions.previewRevoke(user,new MandatePreviewRequestDTO(str(a,"mandate_id")));
            case "preview_schedule" -> actions.previewSchedule(user,new SchedulePreviewRequestDTO(str(a,"source_id"),str(a,"recipient_id"),amount(a),str(a,"first_date"),str(a,"end_date"),a.path("day_of_month").asInt(0)));
            default -> throw new BankException("PERMISSION_DENIED","工具不在允许列表中");
        }
        ;
        return json.convertValue(result,Object.class);
    }
    private String str(JsonNode n,String field) {
        if(!n.path(field).isTextual()||n.path(field).asText().isBlank())throw new BankException("VALIDATION_ERROR","缺少参数 "+field);
        return n.path(field).asText();
    }
    private long amount(JsonNode n) {
        var v=n.path("amount_minor");
        if(!v.isIntegralNumber()||!v.canConvertToLong())throw new BankException("VALIDATION_ERROR","金额必须为整数分");
        return v.longValue();
    }
    private WorkflowRequestDTO workflowRequest(JsonNode a) {
        var ids=a.path("mandate_ids");
        if(!ids.isArray()||ids.isEmpty()||ids.size()>10)throw new BankException("VALIDATION_ERROR","需要明确选定的代扣ID列表");
        var mandates=new ArrayList<String>();
        for(var id:ids){
            if(!id.isTextual()||id.asText().isBlank())throw new BankException("VALIDATION_ERROR","代扣ID无效");
            mandates.add(id.asText());
        }
        if(!a.path("day_of_month").isIntegralNumber()||!a.path("only_confirmed_savings").isBoolean())throw new BankException("VALIDATION_ERROR","需要整数日期和节省基准确认");
        return new WorkflowRequestDTO(str(a,"month"),mandates,str(a,"source_id"),str(a,"recipient_id"),str(a,"first_date"),str(a,"end_date"),a.path("day_of_month").intValue(),a.path("only_confirmed_savings").booleanValue());
    }
}
