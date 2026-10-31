"""Two bounded real conversations; never authorizes/submits. Manual review required."""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("v04_evaluation", ROOT / "fixtures/v04/evaluate_conversations.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)
evaluation.CASES = [("refund_order_evidence", [
    ("比较2026-08与2026-09，家电购物支出和退款入账分别是什么情况？", ("3200.00",)),
    ("仅凭这两笔都是3200元，能确定9月退款对应8月那次购物吗？需要什么证据？", ()),
])]
for flag, value in [("--repeats", "1"), ("--max-model-requests", "8")]:
    if not any(arg == flag or arg.startswith(flag + "=") for arg in sys.argv):
        sys.argv.extend([flag, value])
if not any(arg == "--output" or arg.startswith("--output=") for arg in sys.argv):
    sys.argv.extend(["--output", str(ROOT / "target/eval/refund-linkage-conversations.json")])
if __name__ == "__main__":
    raise SystemExit(evaluation.main())
