package cn.safebank.service.query;

import cn.safebank.BankException;
import cn.safebank.dao.ActionMapper;
import cn.safebank.dao.TransactionMapper;
import cn.safebank.entity.TransactionEntity;
import cn.safebank.service.*;
import cn.safebank.vo.AccountVO;
import cn.safebank.vo.BankCardVO;
import cn.safebank.vo.RecurringPaymentVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * 查询引擎：一套语言，六个实体。
 *
 * <p>只读、封闭。所有"合理但没被预置"的问题都落到这里：过滤、分组、排序、取极值、取首末笔，
 * 不再为每种问法写一个工具或一句提示词。写操作（转账/撤销/挂失/申购）不在这里，仍走
 * 工具 → 预览 → 用户确认的既有闭环。</p>
 */
@Service @Profile("agent-gateway")
public class QueryEngine {
    private final TransactionMapper transactions;
    private final AccountService accounts;
    private final CardManagementService cards;
    private final StatementService statements;
    private final AgentMandateReceiptService mandates;
    private final ActionMapper actions;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public QueryEngine(TransactionMapper transactions, AccountService accounts, CardManagementService cards,
                       StatementService statements, AgentMandateReceiptService mandates, ActionMapper actions,
                       Clock clock, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.cards = cards;
        this.statements = statements;
        this.mandates = mandates;
        this.actions = actions;
        this.clock = clock;
        this.json = json;
    }

    /** 供提示词与能力声明使用：实体、可查字段、可分组字段、可排序字段、度量与不可查项。 */
    public static Map<String, Object> entityCatalog() {
        var result = new LinkedHashMap<String, Object>();
        for (var spec : specs().values()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("label", spec.label());
            item.put("fields", spec.fields().values().stream()
                .map(field -> field.name() + ":" + field.kind().name().toLowerCase(Locale.ROOT) + (field.label().isBlank() ? "" : "(" + field.label() + ")"))
                .toList());
            item.put("filterable", spec.filterableFields());
            item.put("groupable", spec.groupableFields());
            item.put("sortable", spec.sortableFields());
            item.put("metrics", List.copyOf(spec.metrics()));
            if (spec.timeField() != null) item.put("time_field", spec.timeField());
            if (spec.measureField() != null) item.put("measure_field", spec.measureField());
            if (!spec.unsupported().isEmpty()) item.put("unsupported", spec.unsupported());
            result.put(spec.name(), item);
        }
        return result;
    }

    public Map<String, Object> execute(String user, String conversationId, JsonNode args) {
        String entity = args.path("entity").asText("");
        var spec = specs().get(entity);
        if (spec == null) {
            throw new BankException("QUERY_NOT_SUPPORTED",
                "不支持的查询实体 " + entity + "；可查：" + String.join("、", specs().keySet()));
        }
        LocalDate start = date(args.path("range").path("start"));
        LocalDate end = date(args.path("range").path("end"));
        return QueryExecutor.run(spec, rows(entity, user, conversationId, start, end), args);
    }

    // ---------------------------------------------------------------- 数据源

    private List<Map<String, Object>> rows(String entity, String user, String conversationId, LocalDate start, LocalDate end) {
        return switch (entity) {
            case "transactions" -> transactionRows(user, start, end);
            case "accounts" -> accountRows(user);
            case "mandates" -> mandateRows(user, conversationId);
            case "cards" -> cardRows(user);
            case "recurring" -> recurringRows(user);
            case "actions" -> actionRows(user);
            default -> throw new BankException("QUERY_NOT_SUPPORTED", "不支持的查询实体 " + entity);
        };
    }

    private List<Map<String, Object>> transactionRows(String user, LocalDate start, LocalDate end) {
        LocalDate from = start != null ? start : businessDate().withDayOfMonth(1);
        LocalDate to = end != null ? end : businessDate();
        if (to.isBefore(from)) to = from;
        var rows = new ArrayList<Map<String, Object>>();
        for (TransactionEntity row : transactions.selectPeriod(user, from, to)) {
            long amount = row.getAmountMinor() == null ? 0L : row.getAmountMinor();
            var item = new LinkedHashMap<String, Object>();
            item.put("booked_on", row.getBookedOn());
            item.put("merchant", Objects.toString(row.getMerchant(), "未提供商户"));
            item.put("category", Objects.toString(row.getCategory(), "未分类"));
            item.put("direction", StatementRules.flow(row.getCategory(), amount) == StatementRules.Flow.EXPENSE ? "支出"
                : StatementRules.flow(row.getCategory(), amount) == StatementRules.Flow.INCOME ? "收入"
                : StatementRules.flow(row.getCategory(), amount) == StatementRules.Flow.INTERNAL ? "内部转账" : "其他");
            item.put("amount_minor", Math.abs(amount));
            item.put("account_id", Objects.toString(row.getAccountId(), ""));
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> accountRows(String user) {
        var rows = new ArrayList<Map<String, Object>>();
        for (AccountVO account : accounts.list(user)) {
            rows.add(new LinkedHashMap<>(Map.of("label", account.label(), "balance_minor", account.balanceMinor())));
        }
        return rows;
    }

    private List<Map<String, Object>> mandateRows(String user, String conversationId) {
        JsonNode snapshot = json.valueToTree(mandates.read(user, conversationId));
        if (snapshot.has("code")) {
            // 前置条件：代扣查询必须先由用户确认范围。这里给出可执行的下一步，而不是把客户推去别的页面。
            throw new BankException("QUERY_PRECONDITION_REQUIRED", "查询代扣授权前需要先在对话里确认查询范围");
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : snapshot.path("mandates")) {
            var item = new LinkedHashMap<String, Object>();
            item.put("merchant", row.path("merchant").asText("未提供商户"));
            item.put("amount_minor", row.path("amount_minor").asLong());
            item.put("billing_interval", row.path("billing_interval").asText("UNKNOWN"));
            item.put("status", row.path("status").asText(""));
            String renewal = row.path("renewal_date").asText("");
            if (!renewal.isBlank()) item.put("renewal_date", renewal);
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> cardRows(String user) {
        var rows = new ArrayList<Map<String, Object>>();
        for (BankCardVO card : cards.list(user)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("label", card.label());
            item.put("masked_number", card.maskedNumber());
            item.put("card_type", card.cardType());
            item.put("status", card.status());
            item.put("per_transaction_limit_minor", card.perTransactionLimitMinor());
            item.put("daily_limit_minor", card.dailyLimitMinor());
            item.put("online_enabled", card.onlineEnabled());
            item.put("overseas_enabled", card.overseasEnabled());
            item.put("account_label", card.accountLabel());
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> recurringRows(String user) {
        var rows = new ArrayList<Map<String, Object>>();
        for (RecurringPaymentVO row : statements.recurring(user)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("merchant", row.merchant());
            item.put("occurrences", row.occurrences());
            item.put("first_seen", row.firstSeen());
            item.put("last_seen", row.lastSeen());
            item.put("min_amount_minor", row.minAmountMinor());
            item.put("max_amount_minor", row.maxAmountMinor());
            item.put("classification", row.classification());
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> actionRows(String user) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : actions.selectByUser(user)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("kind", row.getKind());
            item.put("status", row.getStatus());
            if (row.getExpiresAt() != null) item.put("expires_at", row.getExpiresAt().toLocalDate());
            item.put("has_receipt", row.getReceiptId() != null && !row.getReceiptId().isBlank());
            rows.add(item);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 声明

    private static Map<String, EntitySpec> specs() {
        var result = new LinkedHashMap<String, EntitySpec>();
        result.put("transactions", EntitySpec.of("transactions", "交易流水")
            .time("booked_on").measure("amount_minor")
            .field("booked_on", EntitySpec.Kind.DATE, "记账日", true, true, true)
            .field("merchant", EntitySpec.Kind.TEXT, "商户", true, true, true)
            .field("category", EntitySpec.Kind.TEXT, "分类", true, true, true)
            .field("direction", EntitySpec.Kind.TEXT, "收支方向", true, true, true)
            .field("amount_minor", EntitySpec.Kind.MONEY, "金额（分，取绝对值）", true, false, true)
            .field("account_id", EntitySpec.Kind.TEXT, "账户编号", true, false, true)
            .metrics("count", "sum", "avg", "max", "min", "latest", "earliest")
            .unsupported("消费渠道", "账单数据没有渠道字段")
            .unsupported("商户合同状态", "银行没有商户合同信息")
            .note("金额按绝对值统计，方向由 direction 区分；内部转账单独标记，不计入收支。")
            .build());
        result.put("accounts", EntitySpec.of("accounts", "账户与余额")
            .measure("balance_minor")
            .field("label", EntitySpec.Kind.TEXT, "账户名称", true, true, true)
            .field("balance_minor", EntitySpec.Kind.MONEY, "可用余额（分）", true, false, true)
            .metrics("count", "sum", "avg", "max", "min")
            .unsupported("开户行与网点", "演示数据没有开户机构信息")
            .build());
        result.put("mandates", EntitySpec.of("mandates", "代扣授权")
            .measure("amount_minor")
            .field("merchant", EntitySpec.Kind.TEXT, "服务名称", true, true, true)
            .field("amount_minor", EntitySpec.Kind.MONEY, "约定金额（分）", true, false, true)
            .field("billing_interval", EntitySpec.Kind.TEXT, "收费周期", true, true, true)
            .field("status", EntitySpec.Kind.TEXT, "授权状态", true, true, true)
            .field("renewal_date", EntitySpec.Kind.DATE, "下次约定扣费日", true, false, true)
            .metrics("count", "sum", "avg", "max", "min", "latest", "earliest")
            .unsupported("使用频率", "银行没有服务使用记录")
            .unsupported("商户合同状态", "银行侧授权不等于商户合同")
            .note("只读取当前会话已确认查询范围的快照；授权有效不等于本期已扣款。")
            .build());
        result.put("cards", EntitySpec.of("cards", "银行卡")
            .measure("per_transaction_limit_minor")
            .field("label", EntitySpec.Kind.TEXT, "卡片名称", true, true, true)
            .field("masked_number", EntitySpec.Kind.TEXT, "卡号后四位", false, false, true)
            .field("card_type", EntitySpec.Kind.TEXT, "卡片类型", true, true, true)
            .field("status", EntitySpec.Kind.TEXT, "卡片状态", true, true, true)
            .field("per_transaction_limit_minor", EntitySpec.Kind.MONEY, "单笔限额（分）", true, false, true)
            .field("daily_limit_minor", EntitySpec.Kind.MONEY, "每日限额（分）", true, false, true)
            .field("online_enabled", EntitySpec.Kind.BOOL, "线上交易", true, false, true)
            .field("overseas_enabled", EntitySpec.Kind.BOOL, "境外交易", true, false, true)
            .field("account_label", EntitySpec.Kind.TEXT, "关联账户", true, true, true)
            .metrics("count", "sum", "avg", "max", "min")
            .unsupported("完整卡号", "只展示后四位")
            .unsupported("按卡统计消费", "交易记录没有卡片字段，只能按账户统计")
            .build());
        result.put("recurring", EntitySpec.of("recurring", "周期扣费候选")
            .time("last_seen").measure("max_amount_minor")
            .field("merchant", EntitySpec.Kind.TEXT, "商户", true, true, true)
            .field("occurrences", EntitySpec.Kind.NUMBER, "出现次数", true, false, true)
            .field("first_seen", EntitySpec.Kind.DATE, "首次出现", true, false, true)
            .field("last_seen", EntitySpec.Kind.DATE, "最近出现", true, false, true)
            .field("min_amount_minor", EntitySpec.Kind.MONEY, "最小金额（分）", true, false, true)
            .field("max_amount_minor", EntitySpec.Kind.MONEY, "最大金额（分）", true, false, true)
            .field("classification", EntitySpec.Kind.TEXT, "分类", true, true, true)
            .metrics("count", "sum", "avg", "max", "min", "latest", "earliest")
            .unsupported("下次扣款日", "历史周期只是候选，不能推断下次扣款")
            .unsupported("使用频率", "银行没有服务使用记录")
            .build());
        result.put("actions", EntitySpec.of("actions", "操作与预览")
            .time("expires_at")
            .field("kind", EntitySpec.Kind.TEXT, "操作类型", true, true, true)
            .field("status", EntitySpec.Kind.TEXT, "状态", true, true, true)
            .field("expires_at", EntitySpec.Kind.DATE, "预览有效期", true, false, true)
            .field("has_receipt", EntitySpec.Kind.BOOL, "是否有回执", true, false, true)
            .metrics("count", "latest", "earliest")
            .unsupported("金额与对手方", "操作载荷不对外查询，请在原操作卡查看")
            .build());
        return Collections.unmodifiableMap(result);
    }

    private LocalDate businessDate() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText("");
        if (text.isBlank()) return null;
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            throw new BankException("QUERY_NOT_SUPPORTED", "日期必须是 YYYY-MM-DD：" + text);
        }
    }
}
