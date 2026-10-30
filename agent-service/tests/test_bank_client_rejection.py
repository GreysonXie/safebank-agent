import json,unittest
from safebank_agent.bank_client import BankClient
from safebank_agent.contracts import ProtocolError

class FakeResponse:
    def __init__(self,status,payload):
        self.status_code=status
        self._body=json.dumps(payload).encode()
    def iter_bytes(self):
        yield self._body
    def __enter__(self):return self
    def __exit__(self,*args):return False

class FakeHttp:
    def __init__(self,status,payload):
        self.status=status;self.payload=payload
    def stream(self,*args,**kwargs):
        return FakeResponse(self.status,self.payload)

class BankClientRejectionTest(unittest.TestCase):
    def client(self,status,payload):
        client=BankClient('http://127.0.0.1:1/','x'*64)
        client.http=FakeHttp(status,payload)
        return client
    def test_business_rejection_returns_result_instead_of_failing_the_effect(self):
        for code in ('VALIDATION_ERROR','SAMPLE_INSUFFICIENT','NOT_FOUND','HISTORY_UNAVAILABLE'):
            data=self.client(400,{'code':code,'message':'测试'}).tool({'tool':'wealth_history'},'t')
            self.assertEqual(code,data['code'],code+' 应作为业务结果返回，供回答阶段澄清')
    def test_model_and_unknown_rejections_still_raise(self):
        with self.assertRaises(ProtocolError) as raised:
            self.client(400,{'code':'MODEL_REQUEST_BUDGET_EXCEEDED'}).tool({},'t')
        self.assertEqual('MODEL_REQUEST_BUDGET_EXCEEDED',str(raised.exception))
        with self.assertRaises(ProtocolError) as rejected:
            self.client(400,{'code':'SOMETHING_ELSE'}).tool({},'t')
        self.assertEqual('BANK_CALLBACK_REJECTED',str(rejected.exception))
        with self.assertRaises(ProtocolError) as unavailable:
            self.client(503,{}).tool({},'t')
        self.assertEqual('BANK_CALLBACK_UNAVAILABLE',str(unavailable.exception))
