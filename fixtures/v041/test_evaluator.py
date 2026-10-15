"""Local contract tests: no provider calls, credentials or bank writes."""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("evaluation", Path(__file__).resolve().parents[1] / "v04/evaluate_conversations.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class EvaluatorTest(unittest.TestCase):
    def test_unused_last_request_is_not_spent_on_an_unreviewable_next_turn(self):
        class Opener:
            chat_calls = 0
            def open(self, request, timeout):
                if request.full_url.endswith("/session"):
                    result = {"csrf": "local-test"}
                elif request.full_url.endswith("/chat"):
                    self.chat_calls += 1
                    result = {"text": "specific answer", "answerSource": "MODEL_GROUNDED", "actions": [], "modelUsage": {"request_count": 2}}
                else:
                    result = [{"balance_minor": 100}]
                return io.BytesIO(json.dumps(result).encode())
        opener = Opener()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            with patch.object(evaluation, "CASES", [("local", [("first", ()), ("second", ())])]), patch.object(evaluation.urllib.request, "build_opener", return_value=opener), patch.object(sys, "argv", ["evaluate", "--repeats", "1", "--output", str(output), "--max-model-requests", "3"]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, evaluation.main())
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, opener.chat_calls)
            self.assertEqual(1, report["turns"])
            self.assertEqual(2, report["model_requests_reserved_or_reported"])

    def test_global_request_budget_is_sent_to_server_and_stops_remaining_turns(self):
        class Opener:
            chat_calls = 0
            def open(self, request, timeout):
                if request.full_url.endswith("/session"):
                    result = {"csrf": "local-test"}
                elif request.full_url.endswith("/chat"):
                    self.chat_calls += 1
                    body = json.loads(request.data)
                    self.asserted_limit = body["modelRequestLimit"]
                    result = {"text": "specific answer", "answerSource": "MODEL_GROUNDED", "actions": [], "modelUsage": {"request_count": 2}}
                else:
                    result = [{"balance_minor": 100}]
                return io.BytesIO(json.dumps(result).encode())
        opener = Opener()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            with patch.object(evaluation, "CASES", [("local", [("first", ()), ("second", ())])]), patch.object(evaluation.urllib.request, "build_opener", return_value=opener), patch.object(sys, "argv", ["evaluate", "--repeats", "1", "--output", str(output), "--max-model-requests", "2"]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, evaluation.main())
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(2, opener.asserted_limit)
            self.assertEqual(1, opener.chat_calls)
            self.assertEqual(2, report["model_requests_reserved_or_reported"])
            self.assertIn("budget exhausted", report["abort_reason"])

    def test_balance_error_stops_after_first_chat_and_still_verifies_bank_balance(self):
        class Opener:
            chat_calls = 0
            def open(self, request, timeout):
                if request.full_url.endswith("/chat"):
                    self.chat_calls += 1
                    error = {"code": "MODEL_API_ERROR", "message": "HTTP 402"}
                    raise urllib.error.HTTPError(request.full_url, 402, "balance", {}, io.BytesIO(json.dumps(error).encode()))
                result = {"csrf": "local-test"} if request.full_url.endswith("/session") else [{"balance_minor": 100}]
                return io.BytesIO(json.dumps(result).encode())
        opener = Opener()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            with patch.object(evaluation, "CASES", [("local", [("first", ()), ("second", ())])]), patch.object(evaluation.urllib.request, "build_opener", return_value=opener), patch.object(sys, "argv", ["evaluate", "--repeats", "1", "--output", str(output)]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, evaluation.main())
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, opener.chat_calls)
            self.assertEqual(1, report["turns"])
            self.assertIn("remaining conversations not attempted", report["abort_reason"])
            self.assertTrue(report["records"][0]["balance_unchanged"])
            self.assertFalse(report["records"][0]["passed"])

    def test_changed_bank_balance_fails_even_when_reply_is_model_grounded(self):
        class Opener:
            account_calls = 0
            def open(self, request, timeout):
                if request.full_url.endswith("/session"):
                    result = {"csrf": "local-test"}
                elif request.full_url.endswith("/chat"):
                    result = {"text": "specific answer", "answerSource": "MODEL_GROUNDED", "actions": [], "modelUsage": {"request_count": 1}}
                else:
                    self.account_calls += 1
                    result = [{"balance_minor": 100 if self.account_calls == 1 else 50}]
                return io.BytesIO(json.dumps(result).encode())
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            with patch.object(evaluation, "CASES", [("local", [("first", ())])]), patch.object(evaluation.urllib.request, "build_opener", return_value=Opener()), patch.object(sys, "argv", ["evaluate", "--repeats", "1", "--output", str(output)]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, evaluation.main())
            record = json.loads(output.read_text(encoding="utf-8"))["records"][0]
            self.assertFalse(record["passed"])
            self.assertFalse(record["balance_unchanged"])

    def test_zero_budget_is_rejected_before_any_request(self):
        with patch.object(evaluation.urllib.request, "build_opener") as build, patch.object(sys, "argv", ["evaluate", "--max-model-requests", "0"]), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as rejected:
                evaluation.main()
            self.assertEqual(2, rejected.exception.code)
            build.assert_not_called()


if __name__ == "__main__":
    unittest.main()
