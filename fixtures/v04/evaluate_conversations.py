"""Real model multi-turn business conversations. Never authorize or submit.

Assertions check grounding, selected facts and changing focus. They are not
a complete semantic judge; review the recorded customer-facing replies.
"""
import argparse
import json
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path

CASES = [
    ("large_expenses", [
        ("分析2026-09账单", ("5369.33",)),
        ("核对大额支出", ("2500.00", "2000.00")),
        ("其中那笔维修支出正常吗？", ("维修",)),
        ("先不判断异常，只告诉我这笔维修费需要核对哪些信息，别再复述账单总额", ("维修",)),
    ]),
    ("saving_advice", [
        ("2026-09哪些钱可以省？", ()),
        ("房租短期动不了，我更想从日常消费入手", ()),
        ("订阅是否值得取消？先给建议，不要执行", ()),
        ("你有证据说我没用视频服务吗？", ()),
    ]),
    ("month_comparison", [
        ("比较2026-09和2026-08账单支出", ("5369.33", "6547.92")),
        ("所以支出是增加还是减少，具体差多少？", ("1178.59",)),
        ("哪类支出变化值得我优先检查？", ()),
    ]),
    ("recipient_clarification", [
        ("我要给张三转账，但先别执行", ("同学", "同事")),
        ("同学那位", ()),
        ("先别操作，我还没想好金额，告诉我还需要哪些信息", ("金额",)),
    ]),
    ("billing_cycle", [
        ("查询银行代扣", ("季度",)),
        ("季度的可以当作每月省一百八十元吗？", ("季度",)),
        ("你说的商户合同状态未知具体是什么意思？", ("合同",)),
    ]),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8094")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--case", action="append")
    parser.add_argument("--max-model-requests", type=int, default=18)
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.url)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1") or args.repeats < 1:
        raise SystemExit("Use a localhost URL and positive repeats")
    output = args.output or Path(__file__).resolve().parents[2] / "target/eval/v04-conversations.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    records = []
    abort_reason = ""
    remaining = args.max_model_requests if args.max_model_requests else None
    if args.max_model_requests < 1:
        parser.error("A positive model request budget is required")
    selected = [(name, turns) for name, turns in CASES if not args.case or name in args.case]
    if args.case and set(args.case) - {name for name, _ in CASES}:
        parser.error("Unknown case")

    def save():
        output.write_text(json.dumps({
            "scope": "real model multi-turn queries/advice/clarification; no authorization or financial execution",
            "cases": len(selected), "repeats": args.repeats, "turns": len(records),
            "model_request_budget": args.max_model_requests,
            "model_requests_reserved_or_reported": args.max_model_requests - remaining if remaining is not None else None,
            "passed": sum(r["passed"] for r in records),
            "abort_reason": abort_reason,
            "actual_token_usage": None, "records": records,
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    for repeat in range(1, args.repeats + 1):
        for name, turns in selected:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor())
            csrf = ""
            previous_reply = ""

            def call(path, body=None):
                request = urllib.request.Request(args.url + "/api" + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={"Content-Type": "application/json", "X-CSRF-Token": csrf},
                    method="GET" if body is None else "POST")
                try:
                    with opener.open(request, timeout=125) as response:
                        return json.load(response)
                except urllib.error.HTTPError as exc:
                    error=json.loads(exc.read().decode())
                    raise ValueError(str(error.get("code",exc.code))+": "+str(error.get("message","Request failed"))) from None

            csrf = call("/session")["csrf"]
            initial = call("/accounts")
            for turn, (prompt, expected) in enumerate(turns, 1):
                if remaining is not None and remaining < 2:
                    abort_reason = "Model request budget exhausted; insufficient for generation plus review; remaining conversations not attempted"
                    save()
                    print(abort_reason, flush=True)
                    return 2
                started = time.monotonic()
                result = {}
                failure = ""
                cleanup_ok = True
                balance_ok = True
                reserved = min(6, remaining) if remaining is not None else 12
                if remaining is not None:
                    remaining -= reserved
                try:
                    result = call("/chat", {"text": prompt, "modelRequestLimit": reserved})
                    if remaining is not None:
                        actual = result.get("modelUsage", {}).get("request_count")
                        if not isinstance(actual, int) or isinstance(actual, bool) or not 0 <= actual <= reserved:
                            raise ValueError("Cannot verify provider request accounting; budget not refunded")
                        remaining += reserved - actual
                    text = result.get("text", "")
                    normalized = text.replace(",", "")
                    if result.get("answerSource") != "MODEL_GROUNDED":
                        raise ValueError("Not a model-grounded response: " + str(result.get("answerSource")))
                    if not text or text == previous_reply:
                        raise ValueError("Empty or repeated reply")
                    if "{{" in text or "已返回银行工具结果" in text:
                        raise ValueError("Internal placeholder or old generic reply")
                    for value in expected:
                        if value not in normalized:
                            raise ValueError("Missing expected business fact/focus: " + value)
                    if result.get("actions"):
                        raise ValueError("Unexpected preview: user explicitly asked for advice/clarification")
                    previous_reply = text
                except (ValueError, KeyError, OSError) as exc:
                    failure = str(exc)
                finally:
                    # Only cancel any unintended AWAITING_AUTH previews; never authorize/submit.
                    for action in result.get("actions", []):
                        if action.get("status") == "AWAITING_AUTH":
                            try:
                                call("/actions/" + action["id"] + "/cancel", {})
                            except (KeyError, OSError):
                                cleanup_ok = False
                    try:
                        balance_ok = call("/accounts") == initial
                    except (ValueError, KeyError, OSError):
                        balance_ok = False
                    if not balance_ok:
                        failure = (failure + "; " if failure else "") + "Account state changed or could not be verified"
                record = {"case": name, "repeat": repeat, "turn": turn, "prompt": prompt,
                    "reply": result.get("text", ""), "answer_source": result.get("answerSource"),
                    "trace": result.get("trace", []), "fact_references": result.get("factReferences", []),
                    "passed": not failure, "failure": failure, "preview_cleanup_ok": cleanup_ok,
                    "balance_unchanged": balance_ok,
                    "model_usage": result.get("modelUsage"),
                    "elapsed_ms": round((time.monotonic() - started) * 1000)}
                records.append(record)
                save()
                print(json.dumps({k: record[k] for k in ("case", "repeat", "turn", "passed", "failure")}, ensure_ascii=False), flush=True)
                if "MODEL_API_ERROR" in failure and any(code in failure for code in ("HTTP 401", "HTTP 402", "HTTP 429")):
                    abort_reason = "Provider unavailable: " + failure + "; remaining conversations not attempted"
                    save()
                    print(abort_reason, flush=True)
                    return 2
                if remaining is not None and failure:
                    abort_reason = "Bounded evaluation stopped on failure: " + failure
                    save()
                    print(abort_reason, flush=True)
                    return 2
    print("Report: " + str(output), flush=True)
    return 0 if all(r["passed"] and r["preview_cleanup_ok"] for r in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
