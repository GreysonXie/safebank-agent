package cn.safebank;

import cn.safebank.service.query.EntitySpec;
import cn.safebank.service.query.QueryExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询内核的契约测试：任意"过滤 + 分组 + 排序 + 度量 + 取几条"都必须能算出来；
 * 越界必须给出**可执行**的提示（缺哪个字段、可查哪些字段），而不是一句"无法回答"。
 */
class QueryExecutorTest {
    private final ObjectMapper json = new ObjectMapper();

    private EntitySpec transactions() {
        return EntitySpec.of("transactions", "交易流水")
            .time("booked_on").measure("amount_minor")
            .field("booked_on", EntitySpec.Kind.DATE, "记账日", true, true, true)
            .field("merchant", EntitySpec.Kind.TEXT, "商户", true, true, true)
            .field("category", EntitySpec.Kind.TEXT, "分类", true, true, true)
            .field("direction", EntitySpec.Kind.TEXT, "方向", true, true, true)
            .field("amount_minor", EntitySpec.Kind.MONEY, "金额", true, false, true)
            .metrics("count", "sum", "avg", "max", "min", "latest", "earliest")
            .unsupported("消费渠道", "账单数据没有渠道字段")
            .build();
    }

    private List<Map<String, Object>> rows() {
        return List.of(
            row("2026-09-03", "老张面馆", "餐饮", "支出", 4_280),
            row("2026-09-12", "城市烤肉", "餐饮", "支出", 12_000),
            row("2026-09-20", "地铁", "交通", "支出", 600),
            row("2026-09-25", "工资", "工资", "收入", 1_500_000));
    }

    private Map<String, Object> row(String date, String merchant, String category, String direction, long amount) {
        var row = new LinkedHashMap<String, Object>();
        row.put("booked_on", LocalDate.parse(date));
        row.put("merchant", merchant);
        row.put("category", category);
        row.put("direction", direction);
        row.put("amount_minor", amount);
        return row;
    }

    private Map<String, Object> run(String args) {
        try {
            return QueryExecutor.run(transactions(), rows(), json.readTree(args));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void finds_the_single_most_expensive_expense_and_returns_its_merchant_and_date() {
        var result = run("""
            {"entity":"transactions","where":{"direction":"支出"},
             "orderBy":{"field":"amount_minor","direction":"desc"},"limit":1}
            """);
        var items = (List<?>) result.get("items");
        assertEquals(1, items.size());
        var item = (Map<?, ?>) items.get(0);
        assertEquals("城市烤肉", item.get("merchant"));
        assertEquals(12_000L, ((Number) item.get("amount_minor")).longValue());
        assertEquals(LocalDate.parse("2026-09-12"), item.get("booked_on"));
        assertEquals(3, ((Number) result.get("matched")).intValue());
    }

    @Test
    void honours_range_and_reports_coverage() {
        var result = run("""
            {"entity":"transactions","range":{"start":"2026-09-10","end":"2026-09-30"},"limit":10}
            """);
        assertEquals(3, ((Number) result.get("matched")).intValue());
        var coverage = (Map<?, ?>) result.get("coverage");
        assertEquals("2026-09-10", coverage.get("start"));
        assertEquals("2026-09-30", coverage.get("end"));
    }

    @Test
    void groups_by_category_with_sum_and_count_sorted_by_measure() {
        var result = run("""
            {"entity":"transactions","groupBy":"category","metrics":["count","sum"]}
            """);
        var groups = (List<?>) result.get("groups");
        // 默认按金额降序：工资 1,500,000 分排第一，餐饮 16,280 分第二
        assertEquals("工资", ((Map<?, ?>) groups.get(0)).get("key"));
        var dining = groups.stream().map(group -> (Map<?, ?>) group)
            .filter(group -> "餐饮".equals(group.get("key"))).findFirst().orElseThrow();
        assertEquals(2, ((Number) dining.get("count")).intValue());
        assertEquals(16_280L, ((Number) dining.get("sum_minor")).longValue());
    }

    @Test
    void computes_max_min_avg_and_latest() {
        var result = run("""
            {"entity":"transactions","where":{"category":"餐饮"},"metrics":["max","min","avg","latest","earliest"]}
            """);
        var totals = (Map<?, ?>) result.get("totals");
        assertEquals(12_000L, ((Number) totals.get("max")).longValue());
        assertEquals(4_280L, ((Number) totals.get("min")).longValue());
        assertEquals("2026-09-12", totals.get("latest"));
        assertEquals("2026-09-03", totals.get("earliest"));
    }

    @Test
    void supports_in_and_range_conditions() {
        var in = run("""
            {"entity":"transactions","where":{"category":["餐饮","交通"]}}
            """);
        assertEquals(3, ((Number) in.get("matched")).intValue());
        var range = run("""
            {"entity":"transactions","where":{"amount_minor":{"min":1000,"max":15000}}}
            """);
        assertEquals(2, ((Number) range.get("matched")).intValue());
    }

    @Test
    void unknown_field_tells_the_caller_what_is_queryable() {
        var error = assertThrows(BankException.class, () -> run("""
            {"entity":"transactions","where":{"city":"北京"}}
            """));
        assertEquals("QUERY_NOT_SUPPORTED", error.code);
        assertTrue(error.getMessage().contains("city"));
        assertTrue(error.getMessage().contains("merchant"));
    }

    @Test
    void unsupported_metric_and_bad_limit_are_rejected_with_guidance() {
        assertThrows(BankException.class, () -> run("""
            {"entity":"transactions","metrics":["median"]}
            """));
        assertThrows(BankException.class, () -> run("""
            {"entity":"transactions","limit":999}
            """));
    }

    @Test
    void unsupported_fields_are_declared_in_the_result() {
        var result = run("""
            {"entity":"transactions","limit":1}
            """);
        var unsupported = (List<?>) result.get("unsupported");
        var first = (Map<?, ?>) unsupported.get(0);
        assertEquals("消费渠道", first.get("field"));
        assertNotNull(result.get("basis"));
    }

    @Test
    void null_placeholders_are_treated_as_absent() {
        var result = run("""
            {"entity":"transactions","range":null,"where":null,"metrics":null}
            """);
        assertEquals(4, ((Number) result.get("matched")).intValue());
        var totals = (Map<?, ?>) result.get("totals");
        assertEquals(4, ((Number) totals.get("count")).intValue());
        assertTrue(((Map<?, ?>) result.get("query")).containsKey("metrics"));
    }
}
