package cn.safebank;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;

/** Explains bank tool evidence. Free model text cannot create amounts or execution outcomes. */
public final class BankReplyComposer {
    public record Evidence(String tool,Object data,Map<String,Object> observation) {
        public Evidence(String tool,Object data){this(tool,data,Map.of());}
        public Evidence{observation=observation==null?Map.of():Map.copyOf(observation);}
    }
    private record Category(String name,long amount) {}
    private final ObjectMapper json;
    public BankReplyComposer(ObjectMapper json) { this.json=json; }
    public String composeFocused(cn.safebank.common.ContextTypes.Intent intent,String focusedId,List<Evidence> evidence){
        return composeFocused(intent,focusedId,null,null,evidence);
    }
    public String composeFocused(cn.safebank.common.ContextTypes.Intent intent,String focusedId,String primaryMonth,String comparisonMonth,List<Evidence> evidence){
        if(intent==cn.safebank.common.ContextTypes.Intent.MONTH_COMPARISON){
            var statements=new TreeMap<String,JsonNode>();for(var e:evidence)if(e.tool().equals("get_statement")){var d=json.valueToTree(e.data());if(!d.has("code")&&d.path("categories").isObject())statements.put(d.path("month").asText(),d);}
            if(primaryMonth==null&&!statements.isEmpty())primaryMonth=statements.lastKey();
            if(comparisonMonth==null&&statements.size()>1)comparisonMonth=statements.lowerKey(primaryMonth);
            var current=statements.get(primaryMonth);var baseline=statements.get(comparisonMonth);
            if(current==null||baseline==null)return "比较还缺少其中一个月份的完整分类账单，请补充或查询两个月份。";
            var categories=new TreeSet<String>();current.path("categories").fieldNames().forEachRemaining(categories::add);baseline.path("categories").fieldNames().forEachRemaining(categories::add);
            if(categories.isEmpty())return "两个账单月份均无分类支出记录，无法识别最大类别变化。";
            long max=categories.stream().mapToLong(c->Math.abs(current.path("categories").path(c).asLong()-baseline.path("categories").path(c).asLong())).max().orElse(0);
            var changes=new ArrayList<String>();for(String c:categories){long now=current.path("categories").path(c).asLong(),before=baseline.path("categories").path(c).asLong(),delta=now-before;if(Math.abs(delta)==max)changes.add(c+"："+comparisonMonth+" "+money(before)+" → "+primaryMonth+" "+money(now)+"，"+(delta<0?"减少 ":delta>0?"增加 ":"持平 ")+money(Math.abs(delta)));}
            return "按类别支出差额的绝对值比较，变化最大的是：\n"+String.join("\n",changes)+"。依据为两月完整账单分类合计；某月未列出该分类表示无此类支出记录，合计为零。账单不能证明变化的具体原因。";
        }
        if(intent==cn.safebank.common.ContextTypes.Intent.TRANSACTION_CHECK){
            for(var e:evidence)if(e.tool().equals("get_statement"))for(var row:json.valueToTree(e.data()).path("transactions"))if(focusedId!=null&&row.path("id").asText().equals(focusedId))return row.path("booked_on").asText()+"，商户〔"+row.path("merchant").asText()+"〕，金额 "+money(Math.abs(row.path("amount_minor").asLong()))+"。账单记录不能证明费用合理或存在欺诈；请核对用途、报价和凭证。";
            return "请明确要核对哪笔交易；现有记录不足以判断用途或报价是否合理。";
        }
        final String selectedPrimary=primaryMonth;
        var related=evidence.stream().filter(e->switch(intent){case STATEMENT_ANALYSIS->e.tool().equals("get_statement")&&(selectedPrimary==null||json.valueToTree(e.data()).path("month").asText().equals(selectedPrimary));case MANDATE_QUERY,MANDATE_REVOKE_DRAFT->e.tool().equals("list_mandates")||e.tool().equals("preview_revoke");default->true;}).toList();
        return compose(related);
    }
    private String money(long minor) { return "¥"+String.format(Locale.ROOT,"%,.2f",BigDecimal.valueOf(minor,2)); }
    public String noEvidence(String request) {
        if(request.matches("(?i)\\s*(你好|您好|hello|hi|早上好|晚上好)[！!。，,.?？\\s]*"))return "你好！我可以帮你查询账单、余额和代扣，也可以准备转账或联合储蓄任务。你想先办理哪一项？";
        if(request.contains("转账")||request.contains("转钱")||request.contains("转入"))return "准备转账还需要明确收款人、来源账户和金额；定时计划还需要首次日期、结束日期及每月执行日。本轮没有可用的银行工具证据，不能报告操作成功。";
        if(request.contains("订阅")||request.contains("代扣"))return "请明确要查询或撤销哪一项代扣。银行扣费记录不能说明使用频率，撤销也不等于商户退订或退款。本轮未执行写操作，不能报告操作成功。";
        if(request.contains("账单")||request.contains("支出"))return "请提供要查询的月份（格式为四位年份-两位月份，例如本月可写当前月份）；需要比较时请指定两个月份。本轮尚未取得账单数据，不能报告操作成功。";
        return "本轮没有可用的银行查询证据，也没有执行写操作，不能报告操作成功。请补充查询月份或具体操作参数。";
    }
    public String compose(List<Evidence> evidence) {
        var paragraphs=new ArrayList<String>();
        for(var item:evidence) {
            JsonNode data=json.valueToTree(item.data());
            if(data.has("code"))paragraphs.add("本次请求未完成："+data.path("message").asText("请补充或检查参数")+"（"+data.path("code").asText()+"）。没有因此执行资金动作。");
            else paragraphs.add(switch(item.tool()) {
                case "get_statement" -> statement(data);
                case "get_accounts" -> accounts(data);
                case "list_mandates" -> mandates(data);
                case "detect_recurring_payments" -> recurring(data);
                case "resolve_recipient" -> recipients(data);
                case "list_transfer_schedules" -> schedules(data);
                case "preview_transfer","preview_revoke","preview_schedule","get_action_status" -> action(data);
                case "start_savings_workflow","get_workflow" -> workflow(data);
                default -> "银行工具已返回记录，请查看对应数据详情。";
            });
        }
        return paragraphs.isEmpty()?"本轮没有可用的银行查询证据，也没有执行写操作，不能报告操作成功。请补充查询月份或具体操作参数。":String.join("\n\n",paragraphs);
    }
    private String action(JsonNode data) {
        String kind=data.path("kind").asText(),status=data.path("status").asText();
        String description=switch(kind) {
            case "QUERY_MANDATES" -> "查询范围：本人银行侧代扣列表。本次只读取数据，不转账、不记账。";
            case "TRANSFER" -> "转账："+data.path("source_id").asText()+" → "+data.path("recipient_label").asText(data.path("recipient_id").asText())+"，金额 "+money(data.path("amount_minor").asLong())+"。";
            case "REVOKE" -> revokeDescription(data);
            case "SCHEDULE" -> "定时储蓄计划："+data.path("source_id").asText()+" → "+data.path("recipient_id").asText()+"，每月 "+data.path("day_of_month").asInt()+" 日 "+money(data.path("amount_minor").asLong())+"，首次 "+data.path("first_date").asText()+"，至 "+data.path("end_date").asText()+"。";
            default -> "银行动作编号："+data.path("id").asText()+"。";
        };
        String outcome=switch(status) {
            case "AWAITING_AUTH" -> "尚未执行，正在等待你核对参数并授权。";
            case "AUTHORIZED" -> "已授权但尚未提交，不代表执行成功。";
            case "PENDING" -> "银行正在处理中，尚不能确认完成。请查原动作，不要新建重复操作。";
            case "UNKNOWN" -> "执行结果未知，不能说明已成功或已失败。请查原动作，不要重复新建或提交。";
            case "CANCELLED" -> "此动作已取消，不会继续提交。";
            case "VERIFIED_FAILED" -> "银行已核验失败，原因："+data.path("error_code").asText("未提供错误码")+"。";
            case "VERIFIED_SUCCESS" -> switch(kind) {
                case "TRANSFER" -> "转账已核验完成。";
                case "REVOKE" -> "银行侧代扣授权已核验撤销。";
                case "SCHEDULE" -> "定时计划已核验登记；未来各期转账尚未发生。";
                default -> "该动作已核验完成。";
            };
            default -> "当前状态："+status+"。请核对原动作。";
        };
        if(status.equals("VERIFIED_SUCCESS")&&!data.path("receipt_id").asText("").isBlank())outcome+=" 回执编号："+data.path("receipt_id").asText()+"。";
        return description+"\n"+outcome;
    }
    private String revokeDescription(JsonNode data) {
        String saving="MONTHLY".equals(data.path("billing_interval").asText())&&data.path("estimated_monthly_saving_minor").isIntegralNumber()
            ?"预览对应的预计月度支出减少 "+money(data.path("estimated_monthly_saving_minor").asLong())+"。"
            :"动作证据中的扣费周期为 "+data.path("billing_interval").asText("未记录")+"，不估算月度节省。";
        return "银行侧代扣撤销：商户名称〔"+data.path("merchant").asText()+"〕。"+saving+"不会增加当前余额；商户合同状态未知。";
    }
    private String workflow(JsonNode data) {
        String state=data.path("state").asText();String label=switch(state) {
            case "AWAITING_AUTH"->"等待逐项授权";case "UNKNOWN"->"原动作结果未知，后续步骤暂停";case "PENDING"->"银行正在处理原动作";
            case "VERIFIED_SUCCESS"->"已核验完成定时计划登记，未来转账尚未发生";case "PARTIAL_SUCCESS"->"部分成功，请检查各项结果";
            case "CANCEL_REQUESTED"->"已停止后续步骤，原动作仍待核实";case "CANCELLED"->"后续任务已取消，已完成步骤保留";case "VERIFIED_FAILED"->"未能完成任务";default->state;
        };
        var text=new StringBuilder("联合任务 "+data.path("id").asText()+"，版本 "+data.path("version").asLong()+"："+label+"。\n只计本任务已核验撤销的预计月度节省："+money(data.path("estimatedSavingMinor").asLong())+"。");
        if(data.path("pendingAction").has("id"))text.append("\n当前确认项：").append(action(data.path("pendingAction")));
        return text.append("\n撤销不代表商户合同终止或已收到退款。每个动作都需要单独授权。").toString();
    }
    private String accounts(JsonNode rows) {
        if(rows.isEmpty())return "没有查询到属于当前用户的账户。";
        var text=new StringBuilder("当前账户余额：");long total=0;
        for(var row:rows) { long balance=row.path("balance_minor").asLong();total+=balance;text.append("\n").append(row.path("label").asText()).append("：").append(money(balance)).append("。"); }
        return text.append("\n本人账户合计 ").append(money(total)).append("。本次仅查询，没有转账。").toString();
    }
    private String mandates(JsonNode rows) {
        if(rows.isEmpty())return "没有查询到银行侧代扣授权记录。";
        var text=new StringBuilder("查询到 "+rows.size()+" 项银行侧代扣授权：");long monthly=0;
        for(var row:rows) {
            String cycle=row.path("billing_interval").asText("UNKNOWN"),status=row.path("status").asText();long amount=row.path("amount_minor").asLong();
            text.append("\n商户名称：〔").append(row.path("merchant").asText()).append("〕，").append(money(amount));
            text.append(switch(cycle){case "MONTHLY"->"/月";case "YEARLY"->"/年";case "QUARTERLY"->"/季度";default->"（周期未确认）";});
            text.append("，").append(status.equals("ACTIVE")?"银行侧授权有效":"银行侧授权已撤销").append("。");
            if(status.equals("ACTIVE")&&cycle.equals("MONTHLY"))monthly+=amount;
        }
        return text.append("\n有效且已知月度的代扣金额合计 ").append(money(monthly)).append("/月。查询不会撤销授权；商户合同状态未知。没有使用记录，无法判断哪些订阅不常使用。").toString();
    }
    private String recurring(JsonNode rows) {
        if(rows.isEmpty())return "目前未发现符合现有间隔规则的周期扣费候选；这不证明不存在订阅。没有使用记录，无法判断使用频率。";
        var text=new StringBuilder("发现 "+rows.size()+" 个周期扣费候选：");
        for(var row:rows)text.append("\n商户名称：〔").append(row.path("merchant").asText()).append("〕，").append(row.path("occurrences").asInt()).append(" 次，").append(row.path("first_seen").asText()).append(" 至 ").append(row.path("last_seen").asText()).append("，单笔 ").append(money(row.path("min_amount_minor").asLong())).append(" 至 ").append(money(row.path("max_amount_minor").asLong())).append("。");
        return text.append("\n这些是重复扣费的规则候选，不等于已确认月度订阅；没有使用记录，不能推断使用频率。可结合银行侧代扣列表核对。").toString();
    }
    private String recipients(JsonNode rows) {
        if(rows.isEmpty())return "没有找到符合查询条件的收款人。请补充联系人ID、备注或手机号，当前没有生成转账。";
        var text=new StringBuilder(rows.size()>1?"找到 "+rows.size()+" 位候选，需要你明确选择：":"找到一位收款人：");
        for(var row:rows)text.append("\n").append(row.path("name").asText()).append(" · ").append(row.path("note").asText()).append(" · ").append(row.path("label").asText()).append("（ID：").append(row.path("id").asText()).append("）。");
        return text.append(rows.size()>1?"\n请说明选择哪一位；不会替你猜选或转账。":"\n仅查到联系人，没有转账。若要继续，请明确金额与来源账户。").toString();
    }
    private String schedules(JsonNode rows) {
        if(rows.isEmpty())return "目前没有已登记的定时转账计划。创建计划前需要明确来源、目标、金额和日期，并在预览上授权。";
        var text=new StringBuilder("查询到 "+rows.size()+" 项定时转账计划：");
        for(var row:rows)text.append("\n").append(row.path("source_id").asText()).append(" → ").append(row.path("recipient_id").asText()).append("，每月 ").append(row.path("day_of_month").asInt()).append(" 日 ").append(money(row.path("amount_minor").asLong())).append("，").append(row.path("first_date").asText()).append(" 至 ").append(row.path("end_date").asText()).append("，状态 ").append(row.path("status").asText()).append("。");
        return text.append("\n计划登记不代表未来各期已经转账；执行结果需分别核对回执。").toString();
    }
    private String statement(JsonNode data) {
        long expense=data.path("expense_minor").asLong(),income=data.path("income_minor").asLong();
        int count=data.path("transactions").size();String month=data.path("month").asText();
        if(count==0)return month+" 没有查询到交易记录。本次查询收入和支出均为 "+money(0)+"；这不代表账户余额为零。";
        var text=new StringBuilder(month+" 共 "+count+" 笔交易，收入 "+money(income)+"，支出 "+money(expense)+"，净收支 "+money(income-expense)+"。");
        var categories=new ArrayList<Category>();data.path("categories").fields().forEachRemaining(entry->categories.add(new Category(entry.getKey(),entry.getValue().asLong())));
        categories.sort(Comparator.comparingLong(Category::amount).reversed().thenComparing(Category::name));
        if(!categories.isEmpty()) {
            var top=categories.getFirst();text.append("\n支出最多的类别是 ").append(top.name()).append("：").append(money(top.amount()));
            if(expense>0)text.append("，占总支出 ").append(String.format(Locale.ROOT,"%.1f%%",BigDecimal.valueOf(top.amount()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(expense),4,java.math.RoundingMode.HALF_UP)));
            text.append("。");for(var category:categories.stream().skip(1).limit(4).toList())text.append("\n").append(category.name()).append("：").append(money(category.amount())).append("。");
        }
        long internal=data.path("internal_transfer_minor").asLong();if(internal>0)text.append("\n本人账户内部转账 ").append(money(internal)).append("，已从上述收入与支出中排除。");
        int anomalies=data.path("anomaly_candidates").size();if(anomalies>0)text.append("\n有 ").append(anomalies).append(" 笔大额支出候选，可核对交易明细；金额达到阈值不代表欺诈。");
        return text.toString();
    }
}
