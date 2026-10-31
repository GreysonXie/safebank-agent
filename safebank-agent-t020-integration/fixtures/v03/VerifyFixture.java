import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.h2.tools.RunScript;

// Standalone verifier: opens exactly one in-memory H2, never application/file DBs.
class VerifyFixture {
    static long value(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]);
        try (var db = DriverManager.getConnection("jdbc:h2:mem:v03_synthetic_verify", "sa", "")) {
            // Optional Spring-equivalent schema-before-data path; never data.sql.
            if (args.length > 1) {
                try (var reader = Files.newBufferedReader(Path.of(args[1]), StandardCharsets.UTF_8)) {
                    RunScript.execute(db, reader);
                }
            }
            try (var reader = Files.newBufferedReader(fixture, StandardCharsets.UTF_8)) {
                RunScript.execute(db, reader);
            }
            long transactionCount = value(db, "SELECT COUNT(*) FROM transactions");
            check(transactionCount == 114, "transaction count");
            for (var opening : Map.of("checking", 3000000L, "travel", 2000000L, "other", 0L,
                    "external-a", 0L, "external-b", 0L).entrySet()) {
                String account = opening.getKey();
                long snapshot = value(db, "SELECT balance_minor FROM accounts WHERE id='" + account + "'");
                long history = value(db, "SELECT COALESCE(SUM(amount_minor),0) FROM transactions WHERE account_id='" + account + "'");
                check(snapshot == opening.getValue() + history, "snapshot reconciliation: " + account);
                check(value(db, "SELECT COUNT(*) FROM (SELECT SUM(amount_minor) OVER (ORDER BY booked_on,id) AS running FROM transactions WHERE account_id='"
                        + account + "') WHERE running + " + opening.getValue() + " < 0") == 0, "negative running balance");
            }
            check(value(db, "SELECT COUNT(DISTINCT FORMATDATETIME(booked_on,'yyyy-MM')) FROM transactions WHERE user_id='u1'") == 6, "six months");
            check(value(db, "SELECT COUNT(*) FROM transactions WHERE booked_on < DATE '2026-04-01'") == 0, "empty March");
            check(value(db, "SELECT COUNT(*) FROM recipients WHERE user_id='u1' AND name='张三'") == 2, "same-name recipients");
            check(value(db, "SELECT SUM(amount_minor) FROM transactions WHERE category='内部转账'") == 0, "internal pairs balance");
            check(value(db, "SELECT COUNT(*) FROM (SELECT booked_on,SUM(amount_minor) AS net,COUNT(*) AS n FROM transactions WHERE category='内部转账' GROUP BY booked_on) WHERE net<>0 OR n<>2") == 0, "paired internal dates");
            check(value(db, "SELECT COUNT(*) FROM transactions t LEFT JOIN accounts a ON a.id=t.account_id WHERE a.id IS NULL OR a.user_id<>t.user_id") == 0, "transaction ownership");
            check(value(db, "SELECT COUNT(*) FROM recipients r LEFT JOIN accounts a ON a.id=r.account_id WHERE a.id IS NULL") == 0, "recipient reference");
            check(value(db, "SELECT SUM(amount_minor) FROM mandates WHERE status='ACTIVE' AND billing_interval='MONTHLY'") == 8300, "known monthly saving");
            for (String table : new String[]{"actions","ledger","schedules","occurrences","audit","execution_observations","bank_faults","workflows","workflow_steps"})
                check(value(db, "SELECT COUNT(*) FROM " + table) == 0, "no fabricated runtime evidence: " + table);
            long before = value(db, "SELECT SUM(balance_minor) FROM accounts");
            boolean rejected = false;
            try (var reader = Files.newBufferedReader(fixture, StandardCharsets.UTF_8)) {
                RunScript.execute(db, reader);
            } catch (SQLException expected) {
                rejected = expected.getMessage().contains("SYNTHETIC_FIXTURE_REQUIRES_EMPTY_DATABASE");
            }
            check(rejected, "nonempty DB must reject at guard");
            check(value(db, "SELECT COUNT(*) FROM transactions") == transactionCount, "repeat did not mutate history");
            check(value(db, "SELECT SUM(balance_minor) FROM accounts") == before, "repeat did not mutate balance");
            System.out.println("PASS: fresh H2 load, 6 months, 114 rows, balance history, ownership, paired transfers, monthly/unknown separation, empty runtime evidence, repeat rejection.");
        }
    }
}
