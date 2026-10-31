import unittest
from safebank_agent.models import ManagedModel, MockModel
from safebank_agent.runtime import Runtime


class Bank:
    def __init__(self, managed=True, enabled=True):
        self.config={'managed': managed, 'enabled': enabled, 'revision': 'r1'}
        self.config_calls=0
        self.sent=[]

    def model_configuration(self, token):
        self.config_calls+=1
        return dict(self.config)

    def model_complete(self, request, token):
        self.sent.append((request, token))
        return {'choices': [{'finish_reason': 'stop', 'message': {'content': '{"kind":"general","answer":"OK"}'}}], 'usage': {'total_tokens': 5}}


class ManagedModelTest(unittest.TestCase):
    def runtime(self, bank):
        runtime=Runtime.__new__(Runtime)
        runtime.bank=bank
        runtime.adapter=MockModel()
        runtime.turn_adapter=None
        runtime.token=lambda state: 'volatile-grant'
        return runtime

    def test_adapter_revision_is_stable_within_one_turn(self):
        bank=Bank();runtime=self.runtime(bank)
        first=runtime.selected_adapter({})
        bank.config['revision']='r2'
        self.assertIs(first,runtime.selected_adapter({}))
        self.assertEqual(first.revision,'r1')
        self.assertEqual(bank.config_calls,1)
        runtime.turn_adapter=None
        self.assertEqual(runtime.selected_adapter({}).revision,'r2')

    def test_managed_adapter_forwards_only_revision_payload_and_original_reservation(self):
        bank=Bank();model=ManagedModel(bank,'volatile-grant','r1')
        reservation={'reservationId':'one','operationKey':'key','inputHash':'hash','javaTurnId':'turn','purpose':'plan'}
        payload={'messages':[],'max_tokens':512}
        result=model.complete(payload,reservation)
        self.assertEqual(result['content']['answer'],'OK')
        self.assertEqual(bank.sent,[({'revision':'r1','payload':payload,**reservation},'volatile-grant')])
        self.assertFalse(hasattr(model,'key'))
        self.assertFalse(hasattr(model,'url'))

    def test_local_disable_overrides_existing_environment_model(self):
        runtime=self.runtime(Bank(enabled=False))
        runtime.adapter=object()
        self.assertIsInstance(runtime.selected_adapter({}),MockModel)

    def test_environment_source_retains_existing_adapter(self):
        runtime=self.runtime(Bank(managed=False))
        self.assertIs(runtime.selected_adapter({}),runtime.adapter)

    def test_banks_without_configuration_protocol_remain_compatible(self):
        runtime=self.runtime(object())
        self.assertIs(runtime.selected_adapter({}),runtime.adapter)


if __name__=='__main__':
    unittest.main()
