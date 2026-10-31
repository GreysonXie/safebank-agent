"""真实模型被提供方拒绝时，必须把真实原因带回客户，而不是折叠成"结果尚未确定"。

背景：客户切换到真实模型后看到"模型响应结果尚未确定"，实际是提供方返回 401（API Key 无效）。
"""
import unittest

from safebank_agent.bank_client import BankClient
from safebank_agent.contracts import ModelRequestRejected, ProtocolError


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self._body = body

    def iter_bytes(self):
        yield self._body


class FakeHttp:
    def __init__(self, status, body):
        self.status = status
        self.body = body

    def stream(self, method, url, json=None, headers=None, timeout=None):
        body = self.body
        status = self.status

        class Ctx:
            def __enter__(self_inner):
                return FakeResponse(status, body)

            def __exit__(self_inner, *args):
                return False

        return Ctx()


def client(status, body):
    instance = BankClient('http://127.0.0.1:8135', 'key')
    instance.http = FakeHttp(status, body)
    return instance


class RejectedModelTest(unittest.TestCase):
    def test_provider_auth_rejection_is_definitive(self):
        instance = client(400, b'{"code":"MODEL_REQUEST_REJECTED_401","message":"invalid api key"}')
        with self.assertRaises(ModelRequestRejected) as error:
            instance.model_complete({'revision': 'r1'}, 'token')
        self.assertEqual('MODEL_REQUEST_REJECTED_401', str(error.exception))

    def test_other_definitive_model_codes_keep_their_reason(self):
        instance = client(400, b'{"code":"MODEL_CONFIGURATION_CHANGED"}')
        with self.assertRaises(ProtocolError) as error:
            instance.model_complete({'revision': 'r1'}, 'token')
        self.assertEqual('MODEL_CONFIGURATION_CHANGED', str(error.exception))

    def test_unknown_model_code_is_not_folded_into_a_generic_rejection(self):
        instance = client(409, b'{"code":"MODEL_SOMETHING_NEW"}')
        with self.assertRaises(ProtocolError) as error:
            instance.model_complete({'revision': 'r1'}, 'token')
        self.assertEqual('MODEL_SOMETHING_NEW', str(error.exception), '新错误码要原样上报，便于定位')



    def test_managed_request_body_has_only_the_declared_fields(self):
        """预算预留返回的 remaining/used 等字段曾被原样拼进模型请求体，被 Java 严格绑定拒绝。"""
        from safebank_agent.models import ManagedModel

        captured = {}

        class Bank:
            def model_complete(self, body, token):
                captured.update(body)
                return {'choices': [{'message': {'content': '{"kind":"general","tools":[]}'}, 'finish_reason': 'stop'}]}

        model = ManagedModel(Bank(), 'token', 'revision-1')
        reservation = {'reservationId': 'r-1', 'operationKey': 'k-1', 'inputHash': 'h-1',
                       'javaTurnId': 't-1', 'purpose': 'plan', 'remaining': 9, 'used': 1, 'applies': True}
        model.complete({'messages': [{'role': 'user', 'content': '{}'}], 'max_tokens': 10}, reservation)
        self.assertEqual({'reservationId', 'operationKey', 'inputHash', 'javaTurnId', 'purpose', 'revision', 'payload'},
                         set(captured), '只能发送 Java 声明的字段，不能把预算响应整包回传')
        self.assertEqual('revision-1', captured['revision'])
        self.assertEqual({'messages', 'max_tokens'}, set(captured['payload']))

        instance = client(400, b'{"code":"MODEL_REQUEST_REJECTED_401"}')
        with self.assertRaises(ProtocolError) as error:
            instance.tool({'tool': 'get_statement', 'args': {}}, 'token')
        self.assertEqual('BANK_CALLBACK_REJECTED', str(error.exception))

    def test_business_rejections_still_return_the_payload(self):
        instance = client(400, '{"code":"INSUFFICIENT_FUNDS","message":"余额不足"}'.encode('utf-8'))
        payload = instance.tool({'tool': 'preview_transfer', 'args': {}}, 'token')
        self.assertEqual('INSUFFICIENT_FUNDS', payload['code'])


if __name__ == '__main__':
    unittest.main()
