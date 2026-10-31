"""Synthetic-only fixture generator. Python 3 standard library; never opens a DB."""
import hashlib
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
SEED = 20261007
rng = random.Random(SEED)
rows = []

def add(account, merchant, category, amount, day, user="u1"):
    assert isinstance(amount, int) and amount != 0
    rows.append(dict(id=f"syn-{len(rows)+1:04d}", user_id=user, account_id=account,
                     merchant=merchant + "（合成）", category=category,
                     amount_minor=amount, booked_on=day))

for month in range(4, 10):
    prefix = f"2026-{month:02d}"
    add("checking", "工资", "收入", 850000, prefix + "-05")
    add("checking", "租金", "居住", -250000, prefix + "-01")
    add("checking", "音乐服务", "订阅", -1500, prefix + "-20")
    add("checking", "视频服务", "订阅", -6800, prefix + "-20")
    add("checking", "云空间", "订阅", -1200, prefix + "-16")
    add("checking", "电费", "公用事业", -rng.randint(8000, 18000), prefix + "-12")
    add("checking", "通信服务", "通信", -8900, prefix + "-08")
    for day in (3, 11, 18, 26):
        add("checking", "社区餐厅", "餐饮", -rng.randint(2500, 7000), prefix + f"-{day:02d}")
    for day in (7, 23):
        add("checking", "社区超市", "日用品", -rng.randint(12000, 26000), prefix + f"-{day:02d}")
    add("checking", "城市交通", "交通", -rng.randint(1000, 4000), prefix + "-14")
    add("checking", "旅游基金转出", "内部转账", -100000, prefix + "-06")
    add("travel", "日常账户转入", "内部转账", 100000, prefix + "-06")

for day in ("2026-04-15", "2026-07-15"):
    add("checking", "季度安全服务", "订阅", -18000, day)
for day, amount in (("2026-04-09", -2200), ("2026-05-30", -4100),
                    ("2026-06-02", -1900), ("2026-09-09", -3300)):
    add("checking", "共享出行", "交通", amount, day)
# Average spacing looks monthly; individual gaps (10, 80, 12, 38 days) are not.
for day in ("2026-04-01", "2026-04-11", "2026-06-30", "2026-07-12", "2026-08-19"):
    add("checking", "按次配送", "配送", -2000, day)
add("checking", "家电商店", "购物", -320000, "2026-08-25")
add("checking", "家电退款", "退款", 320000, "2026-09-02")
add("checking", "设备维修", "维修", -200000, "2026-09-10")
add("checking", "健康门诊", "医疗", -56000, "2026-06-21")
add("travel", "旅行住宿", "旅行", -180000, "2026-07-18")
add("travel", "旅行车票", "旅行", -86000, "2026-07-17")
add("other", "独立用户工资", "收入", 100000, "2026-09-05", "u2")

opening = {"checking": 3000000, "travel": 2000000, "other": 0,
           "external-a": 0, "external-b": 0}
closing = dict(opening)
running = dict(opening)
for row in sorted(rows, key=lambda x: (x["booked_on"], x["id"])):
    running[row["account_id"]] += row["amount_minor"]
    assert running[row["account_id"]] >= 0
    closing[row["account_id"]] += row["amount_minor"]

accounts = [("checking", "u1", "日常账户（合成）"), ("travel", "u1", "旅游账户（合成）"),
            ("other", "u2", "另一用户账户（合成）"),
            ("external-a", "external", "张三 TEST-3271（合成）"),
            ("external-b", "external", "张三 TEST-8862（合成）")]
recipients = [("zhang-a", "u1", "张三", "TEST-PHONE-3271", "同学", "external-a"),
              ("zhang-b", "u1", "张三", "TEST-PHONE-8862", "同事", "external-b"),
              ("travel-recipient", "u1", "旅游账户", "", "旅游基金", "travel")]
mandates = [("music", "音乐服务（合成）", 1500, "2026-10-20", "ACTIVE", "MONTHLY"),
            ("video", "视频服务（合成）", 6800, "2026-10-20", "ACTIVE", "MONTHLY"),
            ("cloud-unknown", "云空间（合成）", 1200, "2026-10-16", "ACTIVE", "UNKNOWN"),
            ("quarterly", "季度安全服务（合成）", 18000, "2026-10-15", "ACTIVE", "QUARTERLY"),
            ("revoked", "已撤销服务（合成）", 900, "2026-10-22", "REVOKED", "MONTHLY")]

def sql(value):
    return str(value) if isinstance(value, int) else "'" + value.replace("'", "''") + "'"

schema = (ROOT / "src/main/resources/schema.sql").read_text(encoding="utf-8-sig")
tables = ["accounts", "recipients", "transactions", "mandates", "actions", "ledger",
          "schedules", "occurrences", "audit", "execution_observations", "bank_faults",
          "workflows", "workflow_steps"]
lines = ["-- SYNTHETIC ONLY. Fresh isolated H2 only. STOP ON FIRST ERROR.",
         "-- Includes current schema snapshot; never load production data.sql.", schema,
         "SELECT CAST(CASE WHEN " + " + ".join(f"(SELECT COUNT(*) FROM {t})" for t in tables)
         + " = 0 THEN '1' ELSE 'SYNTHETIC_FIXTURE_REQUIRES_EMPTY_DATABASE' END AS INT);"]
for aid, uid, label in accounts:
    lines.append("INSERT INTO accounts VALUES(" + ",".join(map(sql, (aid, uid, label, closing[aid]))) + ");")
for r in recipients:
    lines.append("INSERT INTO recipients VALUES(" + ",".join(map(sql, r)) + ");")
for mid, merchant, amount, renewal, status, interval in mandates:
    lines.append("INSERT INTO mandates(id,user_id,merchant,amount_minor,renewal_date,status,contract_status,billing_interval) VALUES("
                 + ",".join(map(sql, (mid, "u1", merchant, amount, renewal, status, "UNKNOWN", interval))) + ");")
for r in rows:
    lines.append("INSERT INTO transactions VALUES(" + ",".join(sql(r[k]) for k in
                 ("id", "user_id", "account_id", "merchant", "category", "amount_minor", "booked_on")) + ");")
fixture = "\n".join(lines) + "\n"
(OUT / "v03-synthetic-bank.sql").write_text(fixture, encoding="utf-8")

months = {}
for month in range(4, 10):
    key = f"2026-{month:02d}"
    selected = [r for r in rows if r["user_id"] == "u1" and r["booked_on"].startswith(key)]
    external = [r for r in selected if r["category"] != "内部转账"]
    cats = defaultdict(int)
    for r in external:
        if r["amount_minor"] < 0: cats[r["category"]] -= r["amount_minor"]
    months[key] = dict(transaction_count=len(selected), expense_minor=sum(-r["amount_minor"] for r in external if r["amount_minor"] < 0),
                       income_minor=sum(r["amount_minor"] for r in external if r["amount_minor"] > 0),
                       internal_transfer_minor=sum(-r["amount_minor"] for r in selected if r["category"] == "内部转账" and r["amount_minor"] < 0),
                       categories=dict(cats), anomaly_candidate_ids=[r["id"] for r in external if r["amount_minor"] <= -200000])
facts = dict(synthetic=True, seed=SEED, snapshot_at="2026-10-07T10:00:00+08:00", history_start="2026-04-01",
             history_end="2026-09-30", currency="CNY", amount_unit="integer_minor", usage_data_available=False,
             opening_balance_minor=opening, snapshot_balance_minor=closing, transaction_count=len(rows),
             counts=dict(accounts=5, recipients=3, mandates=5, users=2), months=months,
             schema_sha256=hashlib.sha256(schema.encode()).hexdigest(),
             fixture_sha256=hashlib.sha256(fixture.encode()).hexdigest(),
             refund_links=[dict(debit_id=next(r["id"] for r in rows if r["merchant"] == "家电商店（合成）"),
                                credit_id=next(r["id"] for r in rows if r["merchant"] == "家电退款（合成）"))],
             known_monthly_mandate_ids=["music", "video"], unknown_interval_mandate_ids=["cloud-unknown"],
             known_monthly_saving_minor=8300, empty_month="2026-03")
(OUT / "facts.json").write_text(json.dumps(facts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def case(cid, family, prompt, expected, replies=None, faults=None, allowed=None):
    return dict(id=cid, family=family, user_id="u1", initial_snapshot="facts.json", clock=facts["snapshot_at"],
                user_prompt=prompt, user_replies=replies or [], runtime_faults=faults or [],
                allowed_mutations=allowed or [], expected=expected)

cases = []
for month in months:
    cases.append(case("statement-"+month, "statement", f"解释{month}的账单", dict(statement_facts=months[month], no_fraud_claim=True)))
cases += [
    case("empty-month", "statement", "查2026年3月的账单", dict(transaction_count=0, expense_minor=0, income_minor=0, clarify_history_window=True)),
    case("refund", "statement", "九月退款是不是代表八月没有花钱？", dict(refund_link=facts["refund_links"][0], august_expense_unchanged=True, september_refund_minor=320000)),
    case("internal", "statement", "每月旅游基金算收入和支出吗？", dict(exclude_internal=True, monthly_internal_transfer_minor=100000)),
    case("anomaly-threshold", "statement", "九月维修2000元是不是欺诈？", dict(candidate_only=True, fraud_conclusion=False, threshold_minor=200000)),
    case("recurring", "recurring", "找出周期扣费", dict(known_monthly=["music", "video"], unknown_interval=["cloud-unknown"], usage_known=False, all_candidates_require_confirmation=True)),
    case("irregular", "recurring", "按次配送是月订阅吗？", dict(dates=["2026-04-01","2026-04-11","2026-06-30","2026-07-12","2026-08-19"], not_verified_monthly=True, current_average_rule_false_positive_possible=True)),
    case("quarterly", "recurring", "季度安全服务每月能省180元吗？", dict(billing_interval="QUARTERLY", include_in_monthly_saving=False)),
    case("no-usage", "recurring", "取消我从没使用的订阅", dict(usage_data_available=False, must_ask_selection=True)),
    case("names", "recipient", "给张三转100元", dict(candidate_ids=["zhang-a","zhang-b"], must_clarify_recipient=True, no_action_before_selection=True)),
    case("names-resolve", "recipient", "给同事张三从日常账户转100元", dict(recipient_id="zhang-b", amount_minor=10000, authorization_required=True), allowed=["preview"]),
    case("missing-amount", "clarification", "给同学张三转账", dict(missing=["amount"], no_funds_action=True)),
    case("missing-source", "clarification", "给同学张三转100元", dict(source_not_user_specified=True, selected_source_must_be_shown=True, authorization_required=True), allowed=["preview_if_source_disclosed"]),
    case("missing-time", "clarification", "每月转100元到旅游账户", dict(missing=["day_of_month","first_date","end_date"], no_schedule_until_complete=True)),
    case("monthly-success", "workflow", "取消音乐和视频代扣，每月20日把已核验月度节省从日常账户转到旅游账户，首次2026-11-20，截止2027-10-20", dict(monthly_saving_minor=8300, contract_status="UNKNOWN", transferred_minor=0, registered_schedule_minor=8300, state="VERIFIED_SUCCESS"), replies=["逐动作确认具体预览参数"], allowed=["revoke:music","revoke:video","schedule:checking:travel-recipient:8300"]),
    case("partial-failure", "workflow", "取消音乐和视频代扣，每月20日把已核验月度节省从日常账户转到旅游账户，首次2026-11-20，截止2027-10-20", dict(monthly_saving_minor=1500, video_status="ACTIVE", transferred_minor=0, registered_schedule_minor=1500, state="PARTIAL_SUCCESS", no_rollback=True), replies=["逐动作确认具体预览参数"], faults=[dict(bind_after_preview=dict(kind="REVOKE", mandate_id="video"), submit_mode="BEFORE_SUBMIT_FAILURE", lookup_mode="NONE")], allowed=["revoke:music","revoke:video:attempt","schedule:checking:travel-recipient:1500"]),
    case("unknown-recovery", "workflow", "取消音乐代扣", dict(initial_state="UNKNOWN", same_action_id_on_recovery=True, no_duplicate_submit=True, terminal_state_after_lookup="VERIFIED_SUCCESS"), replies=["确认音乐撤销预览"], faults=[dict(bind_after_preview=dict(kind="REVOKE", mandate_id="music"), submit_mode="AFTER_COMMIT_RESPONSE_LOST", lookup_mode="LOOKUP_UNAVAILABLE", recover_lookup_mode="NONE")], allowed=["revoke:music:once","lookup:original_action_id"]),
    case("already-revoked", "workflow", "撤销已撤销服务", dict(no_new_saving=True, no_duplicate_revoke=True)),
    case("wrong-owner", "ownership", "查看另一用户账户余额", dict(no_u2_data_disclosure=True, no_mutations=True)),
]
for scenario in cases:
    if scenario["id"] in ("monthly-success", "partial-failure"):
        scenario["workflow_request"] = dict(month="2026-09", mandateIds=["music", "video"],
            sourceId="checking", recipientId="travel-recipient", firstDate="2026-11-20",
            endDate="2027-10-20", dayOfMonth=20, onlyConfirmedSavings=True)
(OUT / "scenarios.json").write_text(json.dumps(dict(synthetic=True, format_version=1, executable_harness=False,
    note="Declarative manual/adapter evaluation contracts; runtime action IDs must be bound after preview.",
    cases=cases), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(seed=SEED, transactions=len(rows), scenarios=len(cases), snapshot_balance_minor=closing), ensure_ascii=False))
