"""Bounded two-turn real smoke: summary/comparison, then a focused follow-up.

Read-only. Maximum six model requests by default; manual review required.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("v04_evaluation", ROOT / "fixtures/v04/evaluate_conversations.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)
evaluation.CASES = [("release_summary_comparison_followup", [
    ("分析2026-09账单，并和2026-08比较支出，说明差额和降幅。", ("5369.33", "6547.92", "1178.59")),
    ("分别核对房租和维修这两项大额支出，金额和需要核对的资料是什么？别再复述账单总额。", ("2500.00", "2000.00")),
])]
for flag, value in [("--repeats", "1"), ("--max-model-requests", "6")]:
    if not any(arg == flag or arg.startswith(flag + "=") for arg in sys.argv):
        sys.argv.extend([flag, value])
if not any(arg == "--output" or arg.startswith("--output=") for arg in sys.argv):
    sys.argv.extend(["--output", str(ROOT / "target/eval/release-smoke.json")])
if __name__ == "__main__":
    raise SystemExit(evaluation.main())
