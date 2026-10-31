package cn.safebank.service.query;

import cn.safebank.BankException;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 查询执行器：把"任意组合的只读问题"翻译成过滤 → 聚合 → 排序 → 取数，产出可核对的事实结构。
 *
 * <p>纯函数式：只依赖 {@link EntitySpec} 与已加载的行，不查库、不调模型，便于单测。
 * 越界一律给出**可执行**的说明（缺哪个字段、可查哪些字段），不用"无法回答"糊过去。</p>
 */
public final class QueryExecutor {
    public static final Set<String> KEYS = Set.of("entity", "range", "where", "groupBy", "metrics", "orderBy", "limit");
    private static final Set<String> METRIC_NAMES = Set.of("count", "sum", "avg", "max", "min", "latest", "earliest");
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 10;

    private QueryExecutor() {}

    public static Map<String, Object> run(EntitySpec spec, List<Map<String, Object>> rows, JsonNode args) {
        var argumentKeys = new ArrayList<String>();
        args.fieldNames().forEachRemaining(argumentKeys::add);
        if (!KEYS.containsAll(argumentKeys)) throw invalid("查询参数只接受 entity、range、where、groupBy、metrics、orderBy、limit");
        LocalDate start = date(args.path("range").path("start"));
        LocalDate end = date(args.path("range").path("end"));
        if (start != null && end != null && start.isAfter(end)) throw invalid("开始日期不能晚于结束日期");
        List<String> metrics = metrics(spec, args.path("metrics"));
        int limit = limit(args.path("limit"));

        List<Map<String, Object>> matched = new ArrayList<>();
        for (var row : rows) {
            if (!inRange(spec, row, start, end)) continue;
            if (!matches(spec, row, args.path("where"))) continue;
            matched.add(row);
        }

        String groupBy = text(args.path("groupBy"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", spec.name());
        result.put("entity_label", spec.label());
        result.put("query", echo(args, start, end, metrics, limit));
        result.put("matched", matched.size());
        if (groupBy != null) {
            result.put("groups", groups(spec, matched, groupBy, metrics, limit));
        } else {
            result.put("totals", aggregate(spec, matched, metrics));
            String orderBy = text(args.path("orderBy").path("field"));
            List<Map<String, Object>> ordered = new ArrayList<>(matched);
            if (orderBy != null) {
                var field = spec.field(orderBy);
                if (field == null || !field.sortable()) throw invalid(orderBy + " 不能排序；可排序字段：" + String.join("、", spec.sortableFields()));
                boolean desc = !"asc".equalsIgnoreCase(text(args.path("orderBy").path("direction")));
                ordered.sort((left, right) -> compare(field, left.get(orderBy), right.get(orderBy), desc));
            }
            result.put("items", ordered.stream().limit(limit).map(row -> project(spec, row)).toList());
        }
        result.put("basis", basis(spec, matched.size(), groupBy != null));
        var coverage = new LinkedHashMap<String, Object>();
        if (start != null) coverage.put("start", start.toString());
        if (end != null) coverage.put("end", end.toString());
        result.put("coverage", coverage);
        var unsupported = new ArrayList<Map<String, Object>>();
        spec.unsupported().forEach((field, reason) -> unsupported.add(Map.of("field", field, "reason", reason)));
        result.put("unsupported", unsupported);
        result.put("notes", spec.notes());
        return result;
    }

    // ---------------------------------------------------------------- 参数

    private static List<String> metrics(EntitySpec spec, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of("count");
        if (!node.isArray() || node.isEmpty()) throw invalid("metrics 必须是数组，例如 [\"count\",\"sum\"]");
        var result = new ArrayList<String>();
        for (var item : node) {
            String name = item.asText("");
            if (!METRIC_NAMES.contains(name) || !spec.metrics().contains(name)) {
                throw invalid("不支持度量 " + name + "；可选：" + String.join("、", spec.metrics()));
            }
            if (Set.of("sum", "avg", "max", "min").contains(name) && spec.measureField() == null) {
                throw invalid(spec.label() + " 没有可聚合的金额字段");
            }
            if (Set.of("latest", "earliest").contains(name) && spec.timeField() == null) {
                throw invalid(spec.label() + " 没有时间字段，不能取最近/最早");
            }
            result.add(name);
        }
        return result;
    }

    private static int limit(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return DEFAULT_LIMIT;
        if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > MAX_LIMIT) throw invalid("limit 必须是 1 至 " + MAX_LIMIT + " 的整数");
        return node.asInt();
    }

    private static boolean inRange(EntitySpec spec, Map<String, Object> row, LocalDate start, LocalDate end) {
        if (spec.timeField() == null || (start == null && end == null)) return true;
        LocalDate value = date(row.get(spec.timeField()));
        if (value == null) return true;
        if (start != null && value.isBefore(start)) return false;
        return end == null || !value.isAfter(end);
    }

    private static boolean matches(EntitySpec spec, Map<String, Object> row, JsonNode where) {
        if (where == null || where.isMissingNode() || where.isNull()) return true;
        if (!where.isObject()) throw invalid("where 必须是对象，例如 {\"category\":\"餐饮\"}");
        var names = where.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            var field = spec.field(name);
            if (field == null) throw invalid("没有字段 " + name + "；可查字段：" + String.join("、", spec.fieldNames()));
            if (!field.filterable()) throw invalid(name + " 不能作为筛选条件");
            if (!accept(field, row.get(name), where.get(name))) return false;
        }
        return true;
    }

    private static boolean accept(EntitySpec.Field field, Object value, JsonNode condition) {
        if (condition.isArray()) {
            for (var item : condition) if (equal(field, value, item)) return true;
            return false;
        }
        if (condition.isObject()) {
            var from = condition.path("from");
            var to = condition.path("to");
            var min = condition.path("min");
            var max = condition.path("max");
            if (!from.isMissingNode() && !from.isNull() && !atLeast(field, value, from, true)) return false;
            if (!to.isMissingNode() && !to.isNull() && !atLeast(field, value, to, false)) return false;
            if (!min.isMissingNode() && !min.isNull() && !atLeast(field, value, min, true)) return false;
            if (!max.isMissingNode() && !max.isNull() && !atLeast(field, value, max, false)) return false;
            if (condition.has("contains")) return Objects.toString(value, "").contains(condition.path("contains").asText(""));
            return true;
        }
        return equal(field, value, condition);
    }

    private static boolean equal(EntitySpec.Field field, Object value, JsonNode expected) {
        if (value == null) return false;
        switch (field.kind()) {
            case BOOL -> { return value instanceof Boolean flag && flag == expected.asBoolean(); }
            case MONEY, NUMBER -> { return decimal(value).compareTo(expected.decimalValue()) == 0; }
            case DATE -> { return date(value) != null && date(value).equals(date(expected.asText(""))); }
            default -> { return Objects.toString(value, "").equals(expected.asText("")); }
        }
    }

    private static boolean atLeast(EntitySpec.Field field, Object value, JsonNode bound, boolean lower) {
        if (value == null) return false;
        switch (field.kind()) {
            case MONEY, NUMBER -> {
                int cmp = decimal(value).compareTo(bound.decimalValue());
                return lower ? cmp >= 0 : cmp <= 0;
            }
            case DATE -> {
                LocalDate boundDate = date(bound.asText(""));
                LocalDate target = date(value);
                if (boundDate == null || target == null) return false;
                return lower ? !target.isBefore(boundDate) : !target.isAfter(boundDate);
            }
            default -> { return lower ? Objects.toString(value, "").compareTo(bound.asText("")) >= 0 : Objects.toString(value, "").compareTo(bound.asText("")) <= 0; }
        }
    }

    // ---------------------------------------------------------------- 聚合

    private static Map<String, Object> aggregate(EntitySpec spec, List<Map<String, Object>> rows, List<String> metrics) {
        var result = new LinkedHashMap<String, Object>();
        for (var metric : metrics) result.put(metric, metricValue(spec, rows, metric));
        if (metrics.contains("sum") && spec.measureField() != null) result.put("sum_minor", result.get("sum"));
        return result;
    }

    private static Object metricValue(EntitySpec spec, List<Map<String, Object>> rows, String metric) {
        return switch (metric) {
            case "count" -> rows.size();
            case "sum" -> money(rows.stream().map(row -> decimal(row.get(spec.measureField()))).reduce(BigDecimal.ZERO, BigDecimal::add));
            case "avg" -> rows.isEmpty() ? BigDecimal.ZERO : money(rows.stream().map(row -> decimal(row.get(spec.measureField()))).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(rows.size()), 0, RoundingMode.HALF_UP));
            case "max" -> rows.stream().map(row -> decimal(row.get(spec.measureField()))).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            case "min" -> rows.stream().map(row -> decimal(row.get(spec.measureField()))).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            case "latest", "earliest" -> {
                var dated = rows.stream().filter(row -> date(row.get(spec.timeField())) != null).toList();
                if (dated.isEmpty()) yield "";
                var chosen = dated.stream().reduce((left, right) -> {
                    LocalDate l = date(left.get(spec.timeField())), r = date(right.get(spec.timeField()));
                    int cmp = l.compareTo(r);
                    return ("latest".equals(metric) ? cmp >= 0 : cmp <= 0) ? left : right;
                }).orElseThrow();
                yield date(chosen.get(spec.timeField())).toString();
            }
            default -> "";
        };
    }

    private static List<Map<String, Object>> groups(EntitySpec spec, List<Map<String, Object>> rows, String groupBy, List<String> metrics, int limit) {
        var field = spec.field(groupBy);
        if (field == null) throw invalid("没有字段 " + groupBy + "；可查字段：" + String.join("、", spec.fieldNames()));
        if (!field.groupable()) throw invalid(groupBy + " 不能分组；可分组字段：" + String.join("、", spec.groupableFields()));
        var buckets = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row : rows) buckets.computeIfAbsent(Objects.toString(row.get(groupBy), "未提供"), key -> new ArrayList<>()).add(row);
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : buckets.entrySet()) {
            var group = new LinkedHashMap<String, Object>();
            group.put("key", entry.getKey());
            group.put("label", entry.getKey());
            group.putAll(aggregate(spec, entry.getValue(), metrics));
            result.add(group);
        }
        String sortKey = "count";
        if (spec.measureField() != null && metrics.stream().anyMatch(name -> Set.of("sum", "avg", "max", "min").contains(name))) {
            sortKey = metrics.stream().filter(name -> Set.of("sum", "avg", "max", "min").contains(name)).findFirst().orElse("count");
        }
        final String key = sortKey;
        result.sort((left, right) -> compareValues(left.get("sum_minor") != null && "sum".equals(key) ? left.get("sum_minor") : left.get(key),
                right.get("sum_minor") != null && "sum".equals(key) ? right.get("sum_minor") : right.get(key)));
        return result.stream().limit(limit).toList();
    }

    // ---------------------------------------------------------------- 输出

    private static Map<String, Object> project(EntitySpec spec, Map<String, Object> row) {
        var item = new LinkedHashMap<String, Object>();
        for (var field : spec.fields().values()) if (row.containsKey(field.name())) item.put(field.name(), row.get(field.name()));
        return item;
    }

    private static Map<String, Object> echo(JsonNode args, LocalDate start, LocalDate end, List<String> metrics, int limit) {
        var echo = new LinkedHashMap<String, Object>();
        if (start != null) echo.put("start", start.toString());
        if (end != null) echo.put("end", end.toString());
        if (!args.path("where").isMissingNode() && args.path("where").isObject()) echo.put("where", args.path("where"));
        if (!args.path("groupBy").isMissingNode() && args.path("groupBy").isTextual()) echo.put("group_by", args.path("groupBy").asText());
        if (!args.path("orderBy").isMissingNode() && args.path("orderBy").isObject()) echo.put("order_by", args.path("orderBy"));
        echo.put("metrics", metrics);
        echo.put("limit", limit);
        return echo;
    }

    private static String basis(EntitySpec spec, int matched, boolean grouped) {
        var text = new StringBuilder();
        text.append("仅统计当前用户名下的").append(spec.label()).append("记录；本次匹配 ").append(matched).append(" 条。");
        if (spec.measureField() != null) text.append("金额单位为分。");
        text.append(grouped ? "分组内的度量只基于本次筛选后的记录。" : "度量与排序只基于本次筛选后的记录。");
        if (spec.timeField() != null) text.append("时间口径按 ").append(spec.timeField()).append("。");
        text.append("未做跨期外推或预测。");
        return text.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static int compare(EntitySpec.Field field, Object left, Object right, boolean desc) {
        int result = switch (field.kind()) {
            case MONEY, NUMBER -> decimal(left).compareTo(decimal(right));
            case DATE -> Objects.compare(date(left), date(right), Comparator.nullsLast(Comparator.naturalOrder()));
            case BOOL -> Boolean.compare(Boolean.TRUE.equals(left), Boolean.TRUE.equals(right));
            default -> Objects.toString(left, "").compareTo(Objects.toString(right, ""));
        };
        return desc ? -result : result;
    }

    private static int compareValues(Object left, Object right) {
        if (left instanceof Number l && right instanceof Number r) return Double.compare(r.doubleValue(), l.doubleValue());
        return Objects.toString(right, "").compareTo(Objects.toString(left, ""));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal big) return big;
        if (value instanceof Number number) return BigDecimal.valueOf(number.longValue());
        try { return new BigDecimal(value.toString()); } catch (RuntimeException ignored) { return BigDecimal.ZERO; }
    }

    private static BigDecimal money(BigDecimal value) { return value.setScale(0, RoundingMode.HALF_UP); }

    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate local) return local;
        if (value instanceof OffsetDateTime offset) return offset.toLocalDate();
        String text = value.toString();
        if (text.length() > 10) text = text.substring(0, 10);
        try { return LocalDate.parse(text); } catch (RuntimeException ignored) { return null; }
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String text = node.asText("");
        if (text.isBlank()) return null;
        try { return LocalDate.parse(text); } catch (RuntimeException ignored) { throw invalid("日期必须是 YYYY-MM-DD：" + text); }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    private static BankException invalid(String message) { return new BankException("QUERY_NOT_SUPPORTED", message); }
}
