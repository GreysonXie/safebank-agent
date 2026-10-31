package cn.safebank;

import cn.safebank.dao.AccountMapper;
import cn.safebank.dao.TransactionMapper;
import cn.safebank.dao.projection.RecurringPaymentRow;
import cn.safebank.entity.AccountEntity;
import cn.safebank.entity.TransactionEntity;
import cn.safebank.service.AccountService;
import cn.safebank.service.StatementQueryService;
import cn.safebank.service.StatementService;
import cn.safebank.vo.StatementQueryVO;
import cn.safebank.vo.StatementVO;
import cn.safebank.vo.YearStatementVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账单通用查询引擎：守恒（桶之和等于总计）、与月报/年报等价、粒度对齐、账户维度、
 * 环比、分页、隔离与上限拒绝。
 */
class StatementQueryEngineTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();

    private TransactionEntity row(String user, String id, String account, String category, long amount, String booked) {
        var entity = new TransactionEntity();
        entity.setId(id);
        entity.setUserId(user);
        entity.setAccountId(account);
        entity.setMerchant(id + "商户");
        entity.setCategory(category);
        entity.setAmountMinor(amount);
        entity.setBookedOn(LocalDate.parse(booked));
        return entity;
    }

    /** 夹具：跨月、内部转账、退款收入、上一年、两个账户、其他用户。 */
    private final List<TransactionEntity> sample = List.of(
        row("u1", "t1", "checking", "餐饮", -12345, "2026-01-05"),
        row("u1", "t2", "checking", "收入", 500000, "2026-01-06"),
        row("u1", "t3", "checking", "内部转账", -40000, "2026-02-01"),
        row("u1", "t4", "savings", "购物", -99999, "2026-02-11"),
        row("u1", "t5", "savings", "退款", 320000, "2026-09-30"),
        row("u1", "t6", "checking", "餐饮", -5000, "2025-12-31"),
        row("u2", "o1", "checking", "餐饮", -777777, "2026-03-03"));

    private final Map<String, String> accountLabels = Map.of("checking", "活期账户 6222", "savings", "储蓄账户 8888");

    private TransactionMapper mapper(List<TransactionEntity> rows) {
        return new TransactionMapper() {
            public List<LocalDate> selectBookedDates(String userId) {
                return rows.stream().filter(row -> row.getUserId().equals(userId)).map(TransactionEntity::getBookedOn)
                    .sorted(Comparator.reverseOrder()).toList();
            }

            public TransactionEntity selectActionDebit(String userId, String id) { return null; }

            public List<TransactionEntity> selectPeriod(String userId, LocalDate start, LocalDate end) {
                return rows.stream().filter(row -> row.getUserId().equals(userId)
                    && !row.getBookedOn().isBefore(start) && row.getBookedOn().isBefore(end)).toList();
            }

            public List<RecurringPaymentRow> selectRecurring(String userId) { return List.of(); }

            public int insert(TransactionEntity row) { return 1; }
        };
    }

    private AccountService accounts() {
        return new AccountService(new AccountMapper() {
            public int insert(AccountEntity account) { return 1; }

            public List<AccountEntity> selectByUser(String userId) {
                var result = new ArrayList<AccountEntity>();
                accountLabels.forEach((id, label) -> {
                    var entity = new AccountEntity();
                    entity.setId(id);
                    entity.setUserId(userId);
                    entity.setLabel(label);
                    entity.setBalanceMinor(0L);
                    result.add(entity);
                });
                return result;
            }

            public AccountEntity selectOwned(String userId, String accountId) { return null; }

            public AccountEntity selectById(String accountId) { return null; }

            public AccountEntity lockById(String accountId) { return null; }

            public AccountEntity lockFirstOwned(String userId) { return null; }

            public int debit(String accountId, long amountMinor) { return 1; }

            public int credit(String accountId, long amountMinor) { return 1; }
        });
    }

    private StatementQueryService engine() { return engine(sample); }

    private StatementQueryService engine(List<TransactionEntity> rows) {
        return new StatementQueryService(mapper(rows), accounts(), clock);
    }

    private StatementService legacy(List<TransactionEntity> rows) {
        return new StatementService(mapper(rows), clock);
    }

    private com.fasterxml.jackson.databind.JsonNode args(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ 守恒与等价

    @Test void bucketMetricsAlwaysSumToTotals() {
        var engine = engine();
        var ranges = List.of(
            List.of("2026-01-01", "2026-01-31"),
            List.of("2026-01-01", "2026-10-30"),
            List.of("2025-01-01", "2026-10-30"));
        for (var range : ranges) {
            for (String bucket : List.of("day", "week", "month", "quarter", "year", "none")) {
                String body = "{\"start\":\"" + range.get(0) + "\",\"end\":\"" + range.get(1) + "\",\"bucket\":\"" + bucket + "\"";
                body += ",\"rows\":false}";
                StatementQueryVO result;
                try {
                    result = engine.query("u1", args(body));
                } catch (BankException tooManyBuckets) {
                    assertTrue(tooManyBuckets.getMessage().contains("上限"), "超限必须是明确拒绝：" + tooManyBuckets.getMessage());
                    continue;
                }
                long expense = 0, income = 0, internal = 0;
                int count = 0;
                for (var item : result.buckets()) {
                    expense += item.metrics().expenseMinor();
                    income += item.metrics().incomeMinor();
                    internal += item.metrics().internalTransferMinor();
                    count += item.metrics().transactionCount();
                }
                String label = range + "/" + bucket;
                assertEquals(result.totals().expenseMinor(), expense, "支出守恒 " + label);
                assertEquals(result.totals().incomeMinor(), income, "收入守恒 " + label);
                assertEquals(result.totals().internalTransferMinor(), internal, "内部转账守恒 " + label);
                assertEquals(result.totals().transactionCount(), count, "笔数守恒 " + label);
                assertEquals(result.totals().incomeMinor() - result.totals().expenseMinor(), result.totals().netMinor(), "净额自洽 " + label);
            }
        }
    }

    @Test void monthPresetEqualsLegacyMonthlyStatement() {
        var engine = engine();
        var legacy = legacy(sample);
        for (String month : List.of("2026-01", "2026-02", "2026-09", "2025-12")) {
            var start = LocalDate.parse(month + "-01");
            var end = start.plusMonths(1).minusDays(1);
            var result = engine.query("u1", args("{\"start\":\"" + start + "\",\"end\":\"" + end
                + "\",\"bucket\":\"month\",\"groupBy\":\"category\",\"rows\":{\"offset\":0,\"limit\":50}}"));
            StatementVO monthly = legacy.statement("u1", month);
            assertEquals(monthly.expenseMinor(), result.totals().expenseMinor(), "支出与月报一致 " + month);
            assertEquals(monthly.incomeMinor(), result.totals().incomeMinor(), "收入与月报一致 " + month);
            assertEquals(monthly.internalTransferMinor(), result.totals().internalTransferMinor(), "内部转账与月报一致 " + month);
            assertEquals(monthly.transactions().size(), result.rows().totalCount(), "笔数与月报一致 " + month);
            Map<String, Long> categories = new LinkedHashMap<>();
            for (var group : result.breakdown().groups()) categories.put(group.key(), group.amountMinor());
            assertEquals(monthly.categories(), categories, "分类口径与月报一致 " + month);
        }
    }

    @Test void yearPresetEqualsLegacyYearlyStatement() {
        var engine = engine();
        var legacy = legacy(sample);
        var result = engine.query("u1", args("{\"start\":\"2026-01-01\",\"end\":\"2026-12-31\",\"bucket\":\"month\",\"rows\":false}"));
        YearStatementVO yearly = legacy.yearStatement("u1", "2026");
        assertEquals(yearly.expenseMinor(), result.totals().expenseMinor());
        assertEquals(yearly.incomeMinor(), result.totals().incomeMinor());
        assertEquals(yearly.internalTransferMinor(), result.totals().internalTransferMinor());
        assertEquals(yearly.recordCount(), result.totals().transactionCount());
        assertEquals(12, result.buckets().size(), "全年固定 12 个月桶，空月也如实存在");
        for (int i = 0; i < 12; i++) {
            var expected = yearly.months().get(i);
            var actual = result.buckets().get(i);
            assertEquals(expected.month(), actual.key());
            assertEquals(expected.expenseMinor(), actual.metrics().expenseMinor(), actual.key() + " 支出");
            assertEquals(expected.transactionCount(), actual.metrics().transactionCount(), actual.key() + " 笔数");
        }
    }

    // ------------------------------------------------------------ 粒度对齐

    @Test void weekBucketsFollowIsoWeekAcrossTheYearBoundary() {
        var result = engine().query("u1", args("{\"start\":\"2025-12-28\",\"end\":\"2026-01-04\",\"bucket\":\"week\",\"rows\":false}"));
        assertEquals(List.of("2025-W52", "2026-W01"), result.buckets().stream().map(StatementQueryVO.Bucket::key).toList());
        assertEquals("2025-12-28", result.buckets().get(0).start());
        assertEquals("2025-12-28", result.buckets().get(0).end(), "首桶被区间起点裁剪");
        assertEquals("2025-12-29", result.buckets().get(1).start());
        assertEquals("2026-01-04", result.buckets().get(1).end());
        assertEquals(0, result.buckets().get(0).metrics().expenseMinor(), "2025-12-28 本身没有交易");
        assertEquals(5000, result.buckets().get(1).metrics().expenseMinor(), "2025-12-31 按 ISO 周属于 2026-W01");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("含首尾两天")));
    }

    @Test void dayQuarterAndWholeRangeBucketsCoverTheRangeExactly() {
        var engine = engine();
        var days = engine.query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"bucket\":\"day\",\"rows\":false}"));
        assertEquals(28, days.buckets().size());
        assertEquals("2026-02-01", days.buckets().get(0).key());
        assertEquals("2026-02-28", days.buckets().get(27).key());
        assertEquals(99999, days.buckets().stream().mapToLong(b -> b.metrics().expenseMinor()).sum());

        var quarters = engine.query("u1", args("{\"start\":\"2026-01-15\",\"end\":\"2026-09-30\",\"bucket\":\"quarter\",\"rows\":false}"));
        assertEquals(List.of("2026Q1", "2026Q2", "2026Q3"), quarters.buckets().stream().map(StatementQueryVO.Bucket::key).toList());
        assertEquals("2026-01-15", quarters.buckets().get(0).start());

        var whole = engine.query("u1", args("{\"start\":\"2026-01-15\",\"end\":\"2026-09-30\",\"rows\":false}"));
        assertEquals(1, whole.buckets().size());
        assertEquals("总", whole.buckets().get(0).key());
        assertEquals(whole.totals().expenseMinor(), whole.buckets().get(0).metrics().expenseMinor());
    }

    // ------------------------------------------------------------ 维度、对比、分页

    @Test void accountDimensionSplitsByOwnedAccountWithLabel() {
        var result = engine().query("u1", args("{\"start\":\"2026-01-01\",\"end\":\"2026-08-31\",\"bucket\":\"none\",\"groupBy\":\"account\",\"rows\":{\"offset\":0,\"limit\":5}}"));
        assertEquals("account", result.breakdown().dimension());
        var groups = result.breakdown().groups();
        assertEquals(2, groups.size());
        assertEquals("储蓄账户 8888", groups.get(0).label(), "按支出金额倒序，储蓄含购物 999.99");
        assertEquals(99999, groups.get(0).amountMinor());
        assertEquals("活期账户 6222", groups.get(1).label());
        assertEquals(12345, groups.get(1).amountMinor());
        assertEquals(100.0, groups.get(0).sharePercent() + groups.get(1).sharePercent(), 0.05);
        assertEquals("储蓄账户 8888", result.rows().items().get(0).accountLabel(), "明细同样带账户展示名");
        assertEquals("t4", result.rows().items().get(0).id());
    }

    @Test void previousPeriodComparisonShowsWhereTheDifferenceComesFrom() {
        var result = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"compare\":\"previous_period\",\"rows\":false}"));
        var comparison = result.comparison();
        assertNotNull(comparison);
        assertEquals("previous_period", comparison.mode());
        assertEquals("2026-01-04", comparison.baselineStart());
        assertEquals("2026-01-31", comparison.baselineEnd());
        assertEquals(12345, comparison.baseline().expenseMinor());
        assertEquals(99999 - 12345, comparison.delta().expenseMinor());
        assertFalse(comparison.baselineEmpty());
        var shopping = comparison.groups().stream().filter(group -> group.key().equals("购物")).findFirst().orElseThrow();
        assertEquals(99999, shopping.currentMinor());
        assertEquals(0, shopping.baselineMinor());
        assertEquals(99999, shopping.differenceMinor());
        assertEquals(99999, shopping.differenceMagnitudeMinor(), "增减幅度带正数，避免“少-560.00元”这种叠加");
        assertEquals("增加", shopping.direction());
    }

    @Test void emptyBaselineIsReportedInsteadOfPretendingAChange() {
        var result = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"compare\":\"same_period_last_year\",\"rows\":false}"));
        assertTrue(result.comparison().baselineEmpty());
        assertEquals(0, result.comparison().baseline().expenseMinor());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("该区间没有记录")));
    }

    @Test void rowsPagingIsHonestAboutWhatIsReturned() {
        var page = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"rows\":{\"offset\":0,\"limit\":1}}"));
        assertEquals(2, page.rows().totalCount());
        assertEquals(1, page.rows().items().size());
        assertTrue(page.rows().truncated());
        assertTrue(page.notes().stream().anyMatch(note -> note.contains("明细共 2 笔")));
        assertEquals("t4", page.rows().items().get(0).id(), "按日期倒序");

        var beyond = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"rows\":{\"offset\":5,\"limit\":10}}"));
        assertEquals(0, beyond.rows().items().size());
        assertEquals(2, beyond.rows().totalCount(), "越界分页不改变总笔数");

        var none = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"rows\":false}"));
        assertNull(none.rows());
    }

    // ------------------------------------------------------------ 筛选语义

    @Test void directionAndAmountFiltersFollowTheStatedMeaning() {
        var income = engine().query("u1", args("{\"start\":\"2026-01-01\",\"end\":\"2026-01-31\",\"filter\":{\"direction\":\"收入\"},\"rows\":false}"));
        assertEquals(500000, income.totals().incomeMinor());
        assertEquals(0, income.totals().expenseMinor());
        assertEquals(1, income.breakdown().groups().size());
        assertEquals(500000, income.breakdown().groups().get(0).amountMinor(), "方向为收入时拆分按收入计量");

        var big = engine().query("u1", args("{\"start\":\"2026-01-01\",\"end\":\"2026-12-31\",\"filter\":{\"minAmountMinor\":50000},\"rows\":false}"));
        assertEquals(99999, big.totals().expenseMinor(), "仅保留单笔 500 元以上：购物 999.99");
        assertEquals(0, big.totals().internalTransferMinor(), "内部转账 400 元低于下限");

        var noInternal = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"filter\":{\"excludeInternal\":true},\"rows\":false}"));
        assertEquals(0, noInternal.totals().internalTransferMinor());
        assertEquals(1, noInternal.totals().transactionCount());
    }

    @Test void emptyRangeSaysSoInsteadOfShowingZeroAsIfItWereData() {
        var result = engine().query("u1", args("{\"start\":\"2024-05-01\",\"end\":\"2024-05-31\",\"bucket\":\"month\",\"rows\":false}"));
        assertEquals(0, result.totals().expenseMinor());
        assertEquals(1, result.buckets().size());
        assertNull(result.coverage().firstBookedOn());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("没有交易记录")));
    }

    @Test void otherUsersRowsNeverEnterTheResult() {
        var engine = engine();
        assertEquals(0, engine.query("u1", args("{\"start\":\"2026-03-01\",\"end\":\"2026-03-31\",\"rows\":false}")).totals().expenseMinor());
        assertEquals(777777, engine.query("u2", args("{\"start\":\"2026-03-01\",\"end\":\"2026-03-31\",\"rows\":false}")).totals().expenseMinor());
    }

    // ------------------------------------------------------------ 上限与参数校验

    @Test void limitsAreRejectedWithActionableMessages() {
        var engine = engine();
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-10-30\",\"bucket\":\"day\"}")), "上限");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-10-01\",\"end\":\"2027-12-31\"}")), "业务日期后一年");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2020-01-01\",\"end\":\"2023-01-01\"}")), "回溯");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-09-01\",\"end\":\"2026-08-01\"}")), "不能早于");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\"}")), "end 必填");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"bucket\":\"hour\"}")), "粒度只有");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"groupBy\":\"card\"}")), "拆分维度只有");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"compare\":\"last_month\"}")), "对比只有");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"topN\":0}")), "topN");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"rows\":{\"limit\":99}}")), "limit");
    }

    @Test void unknownKeysAndBadTypesAreRejected() {
        var engine = engine();
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"sql\":\"select 1\"}")), "只接受");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"filter\":{\"merchant\":\"x\"}}")), "filter 只接受");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"filter\":{\"direction\":\"支出中\"}}")), "方向只能");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"filter\":{\"minAmountMinor\":-1}}")), "非负整数分");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-8-1\",\"end\":\"2026-08-31\"}")), "格式应为");
        assertMessageContains(() -> engine.query("u1", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\",\"rows\":{\"offset\":0,\"limit\":1,\"page\":2}}")), "rows 只接受");
    }

    @Test void userIdIsRequiredAndComesFromTheCaller() {
        assertMessageContains(() -> engine().query(null, args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\"}")), "缺少用户身份");
        assertMessageContains(() -> engine().query(" ", args("{\"start\":\"2026-08-01\",\"end\":\"2026-08-31\"}")), "缺少用户身份");
    }

    @Test void notesAlwaysCarryTheSharedRules() {
        var result = engine().query("u1", args("{\"start\":\"2026-02-01\",\"end\":\"2026-02-28\",\"rows\":false}"));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("内部转账不计入收入支出")));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("仅为异常候选")));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("含首尾两天")));
        assertEquals("2026-02-01", result.coverage().firstBookedOn());
        assertEquals("2026-02-11", result.coverage().lastBookedOn());
        assertEquals("2026-10-30", result.coverage().businessDate());
        assertFalse(result.coverage().beyondBusinessDate());
    }

    @Test void futureSegmentIsExplainedInsteadOfSilentlyClipped() {
        var result = engine().query("u1", args("{\"start\":\"2026-10-01\",\"end\":\"2026-12-31\",\"bucket\":\"month\",\"rows\":false}"));
        assertEquals(List.of("2026-10", "2026-11", "2026-12"), result.buckets().stream().map(StatementQueryVO.Bucket::key).toList(),
            "“今年”这种自然口径保留完整月份桶，未来月份如实为 0");
        assertEquals("2026-12-31", result.query().end(), "回显请求区间，不偷偷改成今天");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("区间末段晚于业务日期")));
        assertTrue(engine().query("u1", args("{\"start\":\"2026-11-01\",\"end\":\"2026-11-30\",\"rows\":false}")).notes().stream()
            .anyMatch(note -> note.contains("整体晚于业务日期")));
    }

    private void assertMessageContains(Runnable action, String expected) {
        var error = assertThrows(BankException.class, action::run);
        assertTrue(error.getMessage() != null && error.getMessage().contains(expected),
            "期望错误信息包含「" + expected + "」，实际：" + error.getMessage());
    }
}
