package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.common.SimulationClock;
import cn.safebank.dao.TransactionMapper;
import cn.safebank.entity.TransactionEntity;
import cn.safebank.vo.AccountVO;
import cn.safebank.vo.StatementQueryVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 账单通用查询引擎：一份查询参数 → 一次范围取数 → 一次聚合。
 * <p>月报、年报、组合筛选、任意区间、任意粒度都走这套口径，避免“一种需求一套管道”。</p>
 * <p>安全边界：参数是闭集（枚举 + 数字 + 白名单字段），身份只来自入参 userId（由服务端会话/授权注入），
 * 跨度、桶数、明细条数、TopN 全部有上限，越界确定性拒绝，不做自由 SQL 或表达式。</p>
 */
@Service
public class StatementQueryService {
    public static final Set<String> BUCKETS = new LinkedHashSet<>(List.of("day", "week", "month", "quarter", "year", "none"));
    public static final Set<String> DIMENSIONS = new LinkedHashSet<>(List.of("category", "merchant", "account", "none"));
    public static final Set<String> COMPARES = new LinkedHashSet<>(List.of("none", "previous_period", "same_period_last_year"));
    public static final Set<String> DIRECTIONS = new LinkedHashSet<>(List.of("全部", "支出", "收入"));

    public static final Set<String> QUERY_KEYS = Set.of("start", "end", "bucket", "groupBy", "filter", "compare", "topN", "rows");
    public static final Set<String> FILTER_KEYS = Set.of("direction", "categories", "merchants", "accounts", "minAmountMinor", "maxAmountMinor", "excludeInternal");
    public static final Set<String> ROWS_KEYS = Set.of("offset", "limit");

    /** 每种粒度的桶数上限：超限直接拒绝并提示改用更粗粒度，绝不静默截断。 */
    public static final Map<String, Integer> BUCKET_LIMIT = Map.of("day", 62, "week", 53, "month", 36, "quarter", 12, "year", 10, "none", 1);
    public static final int MAX_SPAN_YEARS = 5;
    public static final int MAX_OFFSET = 200;
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_TOPN = 20;
    public static final int DEFAULT_TOPN = 10;
    static final int MAX_LIST_ITEMS = 20;
    static final int MAX_TEXT_LENGTH = 40;
    static final int MAX_ANOMALIES = 10;

    private final TransactionMapper transactions;
    private final AccountService accounts;
    private final Clock clock;

    public StatementQueryService(TransactionMapper transactions, AccountService accounts, Clock clock) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** 归一化后的查询计划：REST 与工具共用同一套校验。 */
    public record Plan(LocalDate start, LocalDate end, String bucket, String groupBy,
                       StatementQueryVO.Filter filter, String compare, int topN,
                       int offset, int limit, boolean rowsEnabled) {
        public StatementQueryVO.Query echo() {
            return new StatementQueryVO.Query(start.toString(), end.toString(), bucket, groupBy, filter, compare, topN);
        }
    }

    public StatementQueryVO query(String userId, JsonNode args) {
        if (userId == null || userId.isBlank()) throw new BankException("VALIDATION_ERROR", "缺少用户身份");
        Plan plan = plan(args);
        LocalDate businessDate = businessDate();
        // 允许“今年”“本月”这种自然口径包含尚未发生的日期：不做静默裁剪，但把未来段如实说明为尚未发生。
        LocalDate horizon = businessDate.plusDays(366);
        if (plan.start().isAfter(horizon))
            throw new BankException("VALIDATION_ERROR", "查询区间整体晚于业务日期 " + businessDate + " 一年以上，没有任何可查询的记录；请改用真实发生过的日期区间");
        if (plan.end().isAfter(horizon))
            throw new BankException("VALIDATION_ERROR", "查询区间最多到业务日期后一年（" + horizon + "）；请缩短 end");
        if (plan.start().isBefore(businessDate.minusYears(MAX_SPAN_YEARS)))
            throw new BankException("VALIDATION_ERROR", "账单数据最多回溯 " + MAX_SPAN_YEARS + " 年；请把 start 调整到 " + businessDate.minusYears(MAX_SPAN_YEARS) + " 之后");
        List<Slot> slots = slots(plan.start(), plan.end(), plan.bucket());
        int cap = BUCKET_LIMIT.get(plan.bucket());
        if (slots.size() > cap)
            throw new BankException("VALIDATION_ERROR", "该区间按 " + plan.bucket() + " 需要 " + slots.size() + " 个时间桶，超过上限 " + cap
                + "；请改用更粗的粒度（bucket=week、month、quarter、year）或缩短区间");

        List<TransactionEntity> loaded = list(userId, plan.start(), plan.end());
        Map<String, String> labels = accountLabels(userId);
        List<TransactionEntity> matched = loaded.stream().filter(row -> matches(row, plan.filter())).toList();

        var totals = new Acc();
        var bucketAcc = new LinkedHashMap<String, Acc>();
        for (var slot : slots) bucketAcc.put(slot.key(), new Acc());
        var groupAcc = new LinkedHashMap<String, Acc>();
        var anomalies = new ArrayList<TransactionEntity>();
        String measure = "收入".equals(plan.filter().direction()) ? "income" : "expense";
        for (var row : matched) {
            totals.add(row);
            bucketAcc.get(bucketKey(row.getBookedOn(), plan.bucket())).add(row);
            groupAcc.computeIfAbsent(groupKey(row, plan.groupBy()), key -> new Acc()).add(row);
            if (StatementRules.anomalyCandidate(row.getCategory(), row.getAmountMinor())) anomalies.add(row);
        }

        var buckets = slots.stream()
            .map(slot -> new StatementQueryVO.Bucket(slot.key(), slot.key(), slot.start().toString(), slot.end().toString(),
                bucketAcc.get(slot.key()).metrics()))
            .toList();
        var breakdown = breakdown(plan.groupBy(), measure, totals, groupAcc, labels, plan.topN());
        var comparison = comparison(plan, measure, userId, totals, groupAcc, labels);
        var rows = plan.rowsEnabled() ? page(matched, plan.offset(), plan.limit(), labels) : null;
        var coverage = coverage(loaded, businessDate);
        var notes = notes(plan, businessDate, matched, breakdown, rows, comparison);

        return new StatementQueryVO(plan.echo(), totals.metrics(), buckets, breakdown, comparison,
            anomalies.stream().sorted(ANOMALY_ORDER).limit(MAX_ANOMALIES).map(row -> row(row, labels)).toList(),
            rows, coverage, notes);
    }

    // ---------------------------------------------------------------- 参数解析

    public Plan plan(JsonNode args) {
        if (args == null || !args.isObject() || args.isEmpty())
            throw new BankException("VALIDATION_ERROR", "查询参数必须是非空对象，至少给出 start 与 end");
        rejectUnknown(args, QUERY_KEYS, "查询参数只接受 start、end、bucket、groupBy、filter、compare、topN、rows");
        LocalDate start = date(args, "start", true);
        LocalDate end = date(args, "end", true);
        if (end.isBefore(start)) throw new BankException("VALIDATION_ERROR", "end 不能早于 start");
        String bucket = text(args, "bucket", "none", BUCKETS, "粒度只有 day、week、month、quarter、year、none");
        String groupBy = text(args, "groupBy", "category", DIMENSIONS, "拆分维度只有 category、merchant、account、none");
        String compare = text(args, "compare", "none", COMPARES, "对比只有 none、previous_period、same_period_last_year");
        int topN = integer(args, "topN", DEFAULT_TOPN, 1, MAX_TOPN, "topN 必须在 1 到 " + MAX_TOPN + " 之间");

        boolean rowsEnabled = true;
        int offset = 0;
        int limit = DEFAULT_LIMIT;
        JsonNode rows = args.get("rows");
        if (rows != null && !rows.isNull()) {
            if (rows.isBoolean()) {
                rowsEnabled = rows.booleanValue();
            } else if (rows.isObject()) {
                rejectUnknown(rows, ROWS_KEYS, "rows 只接受 offset、limit，或直接写 false 表示不要明细");
                offset = integer(rows, "offset", 0, 0, MAX_OFFSET, "offset 必须在 0 到 " + MAX_OFFSET + " 之间");
                limit = integer(rows, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT, "limit 必须在 1 到 " + MAX_LIMIT + " 之间");
            } else {
                throw new BankException("VALIDATION_ERROR", "rows 必须是 {offset,limit} 或 false");
            }
        }
        return new Plan(start, end, bucket, groupBy, filter(args.get("filter")), compare, topN, offset, limit, rowsEnabled);
    }

    StatementQueryVO.Filter filter(JsonNode node) {
        if (node == null || node.isNull()) return new StatementQueryVO.Filter("全部", List.of(), List.of(), List.of(), null, null, false);
        if (!node.isObject()) throw new BankException("VALIDATION_ERROR", "filter 必须是对象");
        rejectUnknown(node, FILTER_KEYS, "filter 只接受 direction、categories、merchants、accounts、minAmountMinor、maxAmountMinor、excludeInternal");
        String direction = text(node, "direction", "全部", DIRECTIONS, "方向只能是全部、支出或收入");
        List<String> categories = texts(node, "categories", "类别");
        List<String> merchants = texts(node, "merchants", "商户");
        List<String> accounts = texts(node, "accounts", "账户编号");
        Long min = amount(node, "minAmountMinor");
        Long max = amount(node, "maxAmountMinor");
        if (min != null && max != null && min > max)
            throw new BankException("VALIDATION_ERROR", "minAmountMinor 不能大于 maxAmountMinor");
        boolean excludeInternal = false;
        JsonNode flag = node.get("excludeInternal");
        if (flag != null && !flag.isNull()) {
            if (!flag.isBoolean()) throw new BankException("VALIDATION_ERROR", "excludeInternal 必须是 true 或 false");
            excludeInternal = flag.booleanValue();
        }
        return new StatementQueryVO.Filter(direction, categories, merchants, accounts, min, max, excludeInternal);
    }

    // ---------------------------------------------------------------- 分桶

    /** 分桶键的唯一实现：日、ISO 周、月、季、年、整段。 */
    public static String bucketKey(LocalDate date, String bucket) {
        return switch (bucket) {
            case "day" -> date.toString();
            case "week" -> String.format("%d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "month" -> YearMonth.from(date).toString();
            case "quarter" -> date.getYear() + "Q" + ((date.getMonthValue() - 1) / 3 + 1);
            case "year" -> String.valueOf(date.getYear());
            default -> "总";
        };
    }

    record Slot(String key, LocalDate start, LocalDate end) {}

    List<Slot> slots(LocalDate start, LocalDate end, String bucket) {
        var result = new ArrayList<Slot>();
        if ("none".equals(bucket)) {
            result.add(new Slot("总", start, end));
            return result;
        }
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate aligned = align(cursor, bucket);
            LocalDate slotStart = aligned.isBefore(start) ? start : aligned;
            LocalDate slotEnd = alignedEnd(aligned, bucket);
            if (slotEnd.isAfter(end)) slotEnd = end;
            result.add(new Slot(bucketKey(aligned, bucket), slotStart, slotEnd));
            cursor = slotEnd.plusDays(1);
        }
        return result;
    }

    private LocalDate align(LocalDate date, String bucket) {
        return switch (bucket) {
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            case "quarter" -> LocalDate.of(date.getYear(), (date.getMonthValue() - 1) / 3 * 3 + 1, 1);
            case "year" -> LocalDate.of(date.getYear(), 1, 1);
            default -> date;
        };
    }

    private LocalDate alignedEnd(LocalDate aligned, String bucket) {
        return switch (bucket) {
            case "week" -> aligned.plusDays(6);
            case "month" -> aligned.plusMonths(1).minusDays(1);
            case "quarter" -> aligned.plusMonths(3).minusDays(1);
            case "year" -> aligned.plusYears(1).minusDays(1);
            default -> aligned;
        };
    }

    // ---------------------------------------------------------------- 聚合

    /** 四项指标与笔数的一次累加；与月报/年报共用 {@link StatementRules} 的口径。 */
    static final class Acc {
        long expense;
        long income;
        long internal;
        int count;

        void add(TransactionEntity row) {
            long amount = row.getAmountMinor() == null ? 0L : row.getAmountMinor();
            switch (StatementRules.flow(row.getCategory(), amount)) {
                case EXPENSE -> expense += -amount;
                case INCOME -> income += amount;
                case INTERNAL -> internal += -amount;
                case NONE -> { }
            }
            count++;
        }

        long amountOf(String measure) { return "income".equals(measure) ? income : expense; }

        StatementQueryVO.Metrics metrics() {
            return new StatementQueryVO.Metrics(expense, income, income - expense, internal, count);
        }
    }

    private boolean matches(TransactionEntity row, StatementQueryVO.Filter filter) {
        long amount = row.getAmountMinor() == null ? 0L : row.getAmountMinor();
        String category = row.getCategory();
        boolean internal = StatementRules.INTERNAL_TRANSFER.equals(category);
        if (filter.excludeInternal() && internal) return false;
        if (!filter.categories().isEmpty() && !filter.categories().contains(category)) return false;
        if (!filter.merchants().isEmpty() && !filter.merchants().contains(row.getMerchant())) return false;
        if (!filter.accounts().isEmpty() && !filter.accounts().contains(row.getAccountId())) return false;
        long magnitude = Math.abs(amount);
        if (filter.minAmountMinor() != null && magnitude < filter.minAmountMinor()) return false;
        if (filter.maxAmountMinor() != null && magnitude > filter.maxAmountMinor()) return false;
        return switch (filter.direction()) {
            case "支出" -> amount < 0;
            case "收入" -> amount > 0;
            default -> true;
        };
    }

    private String groupKey(TransactionEntity row, String groupBy) {
        return switch (groupBy) {
            case "merchant" -> Objects.toString(row.getMerchant(), "未知商户");
            case "account" -> Objects.toString(row.getAccountId(), "未知账户");
            case "none" -> "总";
            default -> Objects.toString(row.getCategory(), "未分类");
        };
    }

    private String groupLabel(String key, String groupBy, Map<String, String> labels) {
        return "account".equals(groupBy) ? labels.getOrDefault(key, key) : key;
    }

    private StatementQueryVO.Breakdown breakdown(String dimension, String measure, Acc totals,
                                                  Map<String, Acc> groups, Map<String, String> labels, int topN) {
        long base = totals.amountOf(measure);
        var ordered = groups.entrySet().stream()
            .filter(entry -> entry.getValue().amountOf(measure) != 0)
            .sorted(Comparator.<Map.Entry<String, Acc>>comparingLong(entry -> entry.getValue().amountOf(measure)).reversed()
                .thenComparing(Map.Entry::getKey))
            .toList();
        int shown = Math.min(ordered.size(), topN);
        long restAmount = 0;
        int restCount = 0;
        var items = new ArrayList<StatementQueryVO.Group>();
        for (int i = 0; i < ordered.size(); i++) {
            var entry = ordered.get(i);
            long amount = entry.getValue().amountOf(measure);
            if (i < shown) {
                double share = base == 0 ? 0.0 : Math.round(amount * 10000.0 / base) / 100.0;
                items.add(new StatementQueryVO.Group(entry.getKey(), groupLabel(entry.getKey(), dimension, labels),
                    entry.getValue().metrics(), amount, share));
            } else {
                restAmount += amount;
                restCount += 1;
            }
        }
        boolean capped = ordered.size() > shown;
        return new StatementQueryVO.Breakdown(dimension, measure, List.copyOf(items), capped, restAmount, restCount);
    }

    private StatementQueryVO.Comparison comparison(Plan plan, String measure, String userId,
                                                   Acc totals, Map<String, Acc> groups, Map<String, String> labels) {
        if ("none".equals(plan.compare())) return null;
        LocalDate baselineStart;
        LocalDate baselineEnd;
        if ("same_period_last_year".equals(plan.compare())) {
            baselineStart = plan.start().minusYears(1);
            baselineEnd = plan.end().minusYears(1);
        } else {
            long span = ChronoUnit.DAYS.between(plan.start(), plan.end()) + 1;
            baselineEnd = plan.start().minusDays(1);
            baselineStart = baselineEnd.minusDays(span - 1);
        }
        List<TransactionEntity> rows = list(userId, baselineStart, baselineEnd).stream()
            .filter(row -> matches(row, plan.filter())).toList();
        var baseTotals = new Acc();
        var baseGroups = new LinkedHashMap<String, Acc>();
        for (var row : rows) {
            baseTotals.add(row);
            baseGroups.computeIfAbsent(groupKey(row, plan.groupBy()), key -> new Acc()).add(row);
        }
        var keys = new LinkedHashSet<String>();
        keys.addAll(groups.keySet());
        keys.addAll(baseGroups.keySet());
        var compareGroups = keys.stream().map(key -> {
            var current = groups.getOrDefault(key, new Acc());
            var baseline = baseGroups.getOrDefault(key, new Acc());
            return new StatementQueryVO.ComparisonGroup(key, groupLabel(key, plan.groupBy(), labels),
                current.amountOf(measure), baseline.amountOf(measure),
                current.amountOf(measure) - baseline.amountOf(measure), current.count, baseline.count);
        }).sorted(Comparator.<StatementQueryVO.ComparisonGroup>comparingLong(group -> Math.abs(group.differenceMinor())).reversed()
            .thenComparing(StatementQueryVO.ComparisonGroup::key))
            .limit(plan.topN())
            .toList();
        var currentMetrics = totals.metrics();
        var baseMetrics = baseTotals.metrics();
        var delta = new StatementQueryVO.Metrics(
            currentMetrics.expenseMinor() - baseMetrics.expenseMinor(),
            currentMetrics.incomeMinor() - baseMetrics.incomeMinor(),
            currentMetrics.netMinor() - baseMetrics.netMinor(),
            currentMetrics.internalTransferMinor() - baseMetrics.internalTransferMinor(),
            currentMetrics.transactionCount() - baseMetrics.transactionCount());
        return new StatementQueryVO.Comparison(plan.compare(), baselineStart.toString(), baselineEnd.toString(),
            baseMetrics, delta, compareGroups, rows.isEmpty());
    }

    // ---------------------------------------------------------------- 明细与覆盖

    private static final Comparator<TransactionEntity> ANOMALY_ORDER =
        Comparator.comparingLong((TransactionEntity row) -> Math.abs(row.getAmountMinor() == null ? 0L : row.getAmountMinor())).reversed()
            .thenComparing(TransactionEntity::getId);

    private static final Comparator<TransactionEntity> ROW_ORDER =
        Comparator.comparing(TransactionEntity::getBookedOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TransactionEntity::getId)
            .reversed();

    private StatementQueryVO.Rows page(List<TransactionEntity> matched, int offset, int limit, Map<String, String> labels) {
        List<TransactionEntity> ordered = matched.stream().sorted(ROW_ORDER).toList();
        int from = Math.min(offset, ordered.size());
        int to = Math.min(from + limit, ordered.size());
        return new StatementQueryVO.Rows(ordered.size(), offset, limit, to < ordered.size(),
            ordered.subList(from, to).stream().map(row -> row(row, labels)).toList());
    }

    private StatementQueryVO.Row row(TransactionEntity row, Map<String, String> labels) {
        String accountId = row.getAccountId();
        return new StatementQueryVO.Row(row.getId(), row.getMerchant(), row.getCategory(), accountId,
            labels.getOrDefault(accountId, accountId),
            row.getAmountMinor() == null ? 0L : row.getAmountMinor(),
            row.getBookedOn() == null ? null : row.getBookedOn().toString());
    }

    private StatementQueryVO.Coverage coverage(List<TransactionEntity> loaded, LocalDate businessDate) {
        LocalDate first = null;
        LocalDate last = null;
        for (var row : loaded) {
            var booked = row.getBookedOn();
            if (booked == null) continue;
            if (first == null || booked.isBefore(first)) first = booked;
            if (last == null || booked.isAfter(last)) last = booked;
        }
        return new StatementQueryVO.Coverage(businessDate.toString(),
            first == null ? null : first.toString(), last == null ? null : last.toString(),
            last != null && last.isAfter(businessDate));
    }

    // ---------------------------------------------------------------- 口径说明

    private List<String> notes(Plan plan, LocalDate businessDate, List<TransactionEntity> matched,
                               StatementQueryVO.Breakdown breakdown, StatementQueryVO.Rows rows,
                               StatementQueryVO.Comparison comparison) {
        var notes = new ArrayList<String>();
        notes.add("统计区间 " + plan.start() + " 至 " + plan.end() + "（含首尾两天），粒度 " + plan.bucket());
        if (plan.start().isAfter(businessDate))
            notes.add("查询区间整体晚于业务日期 " + businessDate + "，属于尚未发生的时间，没有记录");
        else if (plan.end().isAfter(businessDate))
            notes.add("区间末段晚于业务日期 " + businessDate + "，该部分尚未发生、不计入合计；业务日期之后的桶为 0 不是数据缺失");
        notes.add("内部转账不计入收入支出；本人账户之间互转单列为内部转账");
        notes.add(StatementRules.ANOMALY_RULE);
        if (!"全部".equals(plan.filter().direction()))
            notes.add("只统计" + plan.filter().direction() + "方向的记录（按金额正负判断）");
        if (!plan.filter().categories().isEmpty()) notes.add("类别限定：" + String.join("、", plan.filter().categories()));
        if (!plan.filter().merchants().isEmpty()) notes.add("商户限定：" + String.join("、", plan.filter().merchants()));
        if (!plan.filter().accounts().isEmpty()) notes.add("账户限定：" + String.join("、", plan.filter().accounts()));
        if (plan.filter().minAmountMinor() != null || plan.filter().maxAmountMinor() != null) {
            String range = (plan.filter().minAmountMinor() == null ? "不限" : StatementRules.yuan(plan.filter().minAmountMinor()) + " 元")
                + " 至 " + (plan.filter().maxAmountMinor() == null ? "不限" : StatementRules.yuan(plan.filter().maxAmountMinor()) + " 元");
            notes.add("按单笔绝对金额筛选：" + range + "（含端点）");
        }
        if (plan.filter().excludeInternal()) notes.add("已排除内部转账");
        if (matched.isEmpty()) notes.add("该区间与筛选条件下没有交易记录，各项合计为 0 不代表未发生，可能是筛选条件或数据范围所致");
        if (breakdown.truncated())
            notes.add("拆分只展示前 " + breakdown.groups().size() + " 项，其余 " + breakdown.restCount() + " 项合计 "
                + StatementRules.yuan(breakdown.restAmountMinor()) + " 元");
        if (rows != null && rows.truncated())
            notes.add("明细共 " + rows.totalCount() + " 笔，本次返回第 " + (rows.offset() + 1) + " 至 " + (rows.offset() + rows.items().size()) + " 笔");
        if (comparison != null) {
            notes.add("对比区间 " + comparison.baselineStart() + " 至 " + comparison.baselineEnd()
                + (comparison.baselineEmpty() ? "（该区间没有记录）" : "（" + comparison.baseline().transactionCount() + " 笔）"));
        }
        return List.copyOf(notes);
    }

    // ---------------------------------------------------------------- 基础设施

    private List<TransactionEntity> list(String userId, LocalDate start, LocalDate end) {
        return transactions.selectPeriod(userId, start, end.plusDays(1)).stream()
            .filter(row -> row.getBookedOn() != null)
            .toList();
    }

    private Map<String, String> accountLabels(String userId) {
        if (accounts == null) return Map.of();
        Map<String, String> labels = new LinkedHashMap<>();
        for (AccountVO account : accounts.list(userId)) labels.put(account.id(), account.label());
        return labels;
    }

    public LocalDate businessDate() {
        return clock == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : SimulationClock.businessDate(clock);
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed, String message) {
        var keys = new HashSet<String>();
        node.fieldNames().forEachRemaining(keys::add);
        if (!allowed.containsAll(keys)) throw new BankException("VALIDATION_ERROR", message);
    }

    private LocalDate date(JsonNode node, String name, boolean required) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            if (required) throw new BankException("VALIDATION_ERROR", name + " 必填，格式 YYYY-MM-DD");
            return null;
        }
        if (!value.isTextual()) throw new BankException("VALIDATION_ERROR", name + " 必须是 YYYY-MM-DD 字符串");
        try {
            return LocalDate.parse(value.asText().trim());
        } catch (DateTimeParseException invalid) {
            throw new BankException("VALIDATION_ERROR", name + " 格式应为 YYYY-MM-DD");
        }
    }

    private String text(JsonNode node, String name, String fallback, Set<String> allowed, String message) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual() || !allowed.contains(value.asText().trim()))
            throw new BankException("VALIDATION_ERROR", message);
        return value.asText().trim();
    }

    private int integer(JsonNode node, String name, int fallback, int min, int max, String message) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new BankException("VALIDATION_ERROR", message);
        long number = value.asLong();
        if (number < min || number > max) throw new BankException("VALIDATION_ERROR", message);
        return (int) number;
    }

    private Long amount(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
            throw new BankException("VALIDATION_ERROR", name + " 必须是非负整数分");
        return value.asLong();
    }

    private List<String> texts(JsonNode node, String name, String label) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) throw new BankException("VALIDATION_ERROR", name + " 必须是字符串数组");
        if (value.size() > MAX_LIST_ITEMS)
            throw new BankException("VALIDATION_ERROR", label + "最多同时指定 " + MAX_LIST_ITEMS + " 项");
        var result = new ArrayList<String>(value.size());
        for (var item : value) {
            if (!item.isTextual()) throw new BankException("VALIDATION_ERROR", name + " 必须是字符串数组");
            String text = item.asText().trim();
            if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH)
                throw new BankException("VALIDATION_ERROR", label + "每项必须为 1 至 " + MAX_TEXT_LENGTH + " 个字符");
            result.add(text);
        }
        return List.copyOf(result);
    }
}
