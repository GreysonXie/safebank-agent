import unittest
import json
from safebank_agent.models import MockModel
from safebank_agent.graph import planning
from safebank_agent.contracts import ProtocolError

class PreviewPlanningTest(unittest.TestCase):
 def test_preview_accepts_bounded_semantic_parameters(self):
  for tool,args in [('transfer_preview',{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':50000}),('revoke_preview',{'merchantQuery':'视频服务'})]:
   value={'kind':'analysis','tools':[{'tool':tool,'args':args}]}
   self.assertEqual(planning(value),value)
 def test_preview_rejects_identity_authorization_bad_amount_and_multiple_operations(self):
  args={'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':50000}
  for changed in ({**args,'userId':'u2'},{**args,'userConfirmed':True},{**args,'amountMinor':True},{**args,'amountMinor':-1},{**args,'amountMinor':500.0}):
   with self.assertRaises(ProtocolError):planning({'kind':'analysis','tools':[{'tool':'transfer_preview','args':changed}]})
  with self.assertRaises(ProtocolError):planning({'kind':'analysis','tools':[{'tool':'transfer_preview','args':args},{'tool':'revoke_preview','args':{'merchantQuery':'视频服务'}}]})
 def test_submit_is_never_available(self):
  with self.assertRaises(ProtocolError):planning({'kind':'analysis','tools':[{'tool':'submit','args':{}}]})
 def test_mock_consultation_does_not_create_revoke_preview(self):
  for question in ('能否取消视频服务？','可以取消视频服务吗？','能不能取消视频服务？','取消视频服务有什么影响？'):
   value=MockModel().complete({'messages':[{'content':json.dumps({'question':question})}]})['content']
   self.assertFalse(any(t['tool']=='revoke_preview' for t in value.get('tools',[])))
 def test_mock_carries_original_transfer_through_source_clarification(self):
  value=MockModel().complete({'messages':[{'content':json.dumps({'question':'从日常账户','previous_question':'给同学转500元','previous_plan':{'kind':'clarification','tools':[]}})}]})['content']
  self.assertEqual(value['tools'][0],{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':50000}})
 def test_mock_target_change_keeps_amount_and_only_asks_missing_source(self):
  model=MockModel()
  value=model.complete({'messages':[{'content':json.dumps({'question':'改给张三','previous_plan':{'kind':'analysis','tools':[{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':60000}}]}})}]})['content']
  self.assertEqual(value['tools'][0]['args'],{'sourceQuery':'日常账户','recipientQuery':'张三','amountMinor':60000})
  value=model.complete({'messages':[{'content':json.dumps({'question':'给同学转500元'})}]})['content']
  self.assertEqual(value['question'],'请说明付款账户。')
