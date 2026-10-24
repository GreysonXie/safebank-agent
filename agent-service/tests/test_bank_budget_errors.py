import unittest
import httpx
from safebank_agent.bank_client import BankClient
from safebank_agent.contracts import ProtocolError


class BankBudgetErrorsTest(unittest.TestCase):
    def test_budget_rejections_keep_only_the_allowlisted_code(self):
        for code in ('MODEL_REQUEST_BUDGET_EXCEEDED', 'MODEL_TURN_BUDGET_EXCEEDED', 'MODEL_DEBUG_BUDGET_INVALID'):
            with self.subTest(code=code):
                bank = BankClient('https://bank.example', 'fixture')
                bank.http.close()
                bank.http = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(400, json={'code': code, 'message': 'private server detail'})))
                try:
                    with self.assertRaises(ProtocolError) as error:
                        bank.reserve({}, 'fixture-grant')
                    self.assertEqual(str(error.exception), code)
                    with self.assertRaisesRegex(ProtocolError, '^BANK_CALLBACK_REJECTED$'):
                        bank.tool({}, 'fixture-grant')
                finally:
                    bank.http.close()

    def test_unknown_codes_and_private_messages_are_not_forwarded(self):
        bank = BankClient('https://bank.example', 'fixture')
        bank.http.close()
        bank.http = httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(400, json={'code': 'PRIVATE_DETAIL', 'message': 'sensitive'})))
        try:
            with self.assertRaisesRegex(ProtocolError, '^BANK_CALLBACK_REJECTED$'):
                bank.reserve({}, 'fixture-grant')
        finally:
            bank.http.close()
