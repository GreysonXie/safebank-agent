import copy
import json
import unittest

import httpx

from safebank_agent.bank_client import BankClient, BUSINESS_REJECTIONS
from safebank_agent.contracts import ProtocolError
from safebank_agent.graph import build


def _client(code, status=400):
    bank = BankClient('https://bank.example', 'fixture')
    bank.http.close()
    bank.http = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(status, json={'code': code, 'message': 'server detail not for user'})))
    return bank


class BusinessRejectionTest(unittest.TestCase):
    def test_business_rejections_are_returned_as_results_not_errors(self):
        for code in sorted(BUSINESS_REJECTIONS):
            with self.subTest(code=code):
                bank = _client(code)
                try:
                    result = bank.tool({}, 'fixture-grant')
                    self.assertEqual(result['code'], code)
                finally:
                    bank.http.close()

    def test_model_and_unknown_errors_still_raise(self):
        # 保留目标版既有协议：模型类错误码不按业务拒绝放行；非白名单路径仍折叠为 BANK_CALLBACK_REJECTED。
        for code in ('MODEL_UNAVAILABLE', 'MODEL_API_ERROR', 'MODEL_TURN_BUDGET_EXCEEDED'):
            with self.subTest(code=code):
                bank = _client(code)
                try:
                    with self.assertRaises(ProtocolError) as error:
                        bank.tool({}, 'fixture-grant')
                    self.assertEqual(str(error.exception), 'BANK_CALLBACK_REJECTED')
                finally:
                    bank.http.close()
        bank = _client('SOMETHING_ELSE')
        try:
            with self.assertRaisesRegex(ProtocolError, '^BANK_CALLBACK_REJECTED$'):
                bank.tool({}, 'fixture-grant')
        finally:
            bank.http.close()

    def test_server_failure_keeps_its_existing_meaning(self):
        bank = BankClient('https://bank.example', 'fixture')
        bank.http.close()
        bank.http = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(503, json={})))
        try:
            with self.assertRaisesRegex(ProtocolError, '^BANK_CALLBACK_UNAVAILABLE$'):
                bank.tool({}, 'fixture-grant')
        finally:
            bank.http.close()

    def _draft(self, code):
        class Context:
            def model(self, state, node, index, payload):
                self.payload = json.loads(payload['messages'][-1]['content'])
                return {'content': {'kind': 'general', 'answer': '说明该限制，不编造数值。'}}

            def note(self, *args):
                pass

            def validate(self, *args):
                pass

        state = {'request': {'text': '看这个月的账单', 'turnId': 't', 'expectedVersion': 1, 'modelRequestLimit': 5},
                 'plan': {'kind': 'analysis', 'tools': []},
                 'receipts': [{'tool': 'statement', 'data': {'code': code}, 'facts': {}}],
                 'answer': {'kind': 'general', 'answer': ''}}
        context = Context()
        build(None, context).nodes['draft'].invoke(state)
        return context.payload

    def test_business_rejection_reaches_the_model_as_a_notice(self):
        payload = self._draft('SAMPLE_INSUFFICIENT')
        notices = payload.get('tool_notices') or []
        self.assertTrue(any(item.get('code') == 'SAMPLE_INSUFFICIENT' for item in notices), notices)
        self.assertTrue(any('样本不足' in item.get('message', '') for item in notices), notices)

    def test_insufficient_funds_notice_does_not_claim_execution(self):
        payload = self._draft('INSUFFICIENT_FUNDS')
        notices = payload.get('tool_notices') or []
        message = ' '.join(item.get('message', '') for item in notices)
        self.assertIn('余额不足', message)
        for forbidden in ('已完成', '已执行', '已转账'):
            self.assertNotIn(forbidden, message)
