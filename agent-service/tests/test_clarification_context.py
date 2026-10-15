import copy,json,unittest
from unittest.mock import patch
from safebank_agent.graph import build
from safebank_agent.contracts import ProtocolError

class Context:
 def __init__(self,*answers):self.answers=iter(answers);self.payloads=[]
 def model(self,state,node,index,payload):self.payloads.append(json.loads(payload['messages'][-1]['content']));return {'content':next(self.answers)}

class ClarificationContextTest(unittest.TestCase):
 def test_planner_receives_multiple_original_user_fragments(self):
  context=Context({'kind':'general','tools':[]});history=['给同学转钱','500元']
  build(None,context).nodes['plan'].invoke({'request':{'text':'日常账户'},'previous_question':'500元','clarification_context':history})
  self.assertEqual(context.payloads[0]['clarification_context'],history)
 def test_wait_extends_original_history_without_mutation(self):
  state={'request':{'text':'500元','turnId':'a','expectedVersion':2},'plan':{'kind':'clarification','tools':[],'question':'哪个付款账户？'},'clarification_context':['给同学转钱']};before=copy.deepcopy(state)
  with patch('safebank_agent.graph.interrupt',return_value={'text':'日常账户'}):result=build(None,None).nodes['wait'].invoke(state)
  self.assertEqual(result['clarification_context'],['给同学转钱','500元']);self.assertEqual(state,before)
 def test_history_is_bounded_without_partial_text_or_authority_fields(self):
  from safebank_agent.conversation import bounded_clarification_context
  history=['a'*1100,'b'*1100,{'grant':'never-copy'},'c'*1100,'d'*1100,'500元']
  result=bounded_clarification_context(history)
  self.assertLessEqual(len(result),4);self.assertLessEqual(sum(map(len,result)),4000);self.assertEqual(result[-1],'500元');self.assertTrue(all(x in history for x in result))
 def test_deferral_rejects_stale_preview_before_tools_and_without_spare_budget(self):
  context=Context({'kind':'analysis','tools':[{'tool':'revoke_preview','args':{'merchantQuery':'音乐服务'}}]})
  with self.assertRaisesRegex(ProtocolError,'DEFERRED_OPERATION'):build(None,context).nodes['plan'].invoke({'request':{'text':'先不办','modelRequestLimit':2}})
 def test_deferral_has_one_bounded_plan_repair(self):
  context=Context({'kind':'clarification','tools':[],'question':'请说明付款账户。'},{'kind':'general','tools':[]})
  result=build(None,context).nodes['plan'].invoke({'request':{'text':'先不办','modelRequestLimit':3}})
  self.assertEqual(result['plan']['kind'],'general');self.assertEqual(result['plan_repair'],1);self.assertEqual(context.payloads[1]['validation_error'],'DEFERRED_OPERATION')
 def test_compound_request_is_not_forced_into_literal_deferral(self):
  from safebank_agent.conversation import defers_operation
  self.assertTrue(defers_operation('先不办。'));self.assertTrue(defers_operation('暂时不办'))
  for text in ('先不办，先看预览','不办理转账是什么意思','给同学转500元','换个问题，分析账单'):self.assertFalse(defers_operation(text))
 def test_finite_mock_uses_all_three_parameter_fragments(self):
  from safebank_agent.models import MockModel
  value=MockModel().complete({'messages':[{'content':json.dumps({'question':'日常账户','previous_question':'500元','previous_plan':{'kind':'clarification','tools':[]},'clarification_context':['给同学转钱','500元']})}]})['content']
  self.assertEqual(value['tools'],[{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':50000}}])
 def test_finite_mock_stops_parameter_collection_on_deferral(self):
  from safebank_agent.models import MockModel
  value=MockModel().complete({'messages':[{'content':json.dumps({'question':'先不办','previous_question':'500元','previous_plan':{'kind':'clarification','tools':[]},'operation_deferred':True})}]})['content']
  self.assertEqual(value,{'kind':'general','tools':[]})
 def test_deferred_answer_cannot_reopen_clarification_wait(self):
  context=Context({'kind':'clarification','answer':'请说明付款账户。'})
  result=build(None,context).nodes['draft'].invoke({'request':{'text':'先不办'},'plan':{'kind':'general','tools':[]},'operation_deferred':True,'receipts':[]})
  self.assertEqual(context.payloads[0]['answer_contract']['allowed_kinds'],['general'])
  self.assertEqual(result['draft_error'],'ANSWER_KIND_MISMATCH')
 def test_current_amount_change_wins_over_older_clarification_fragment(self):
  from safebank_agent.models import MockModel
  value=MockModel().complete({'messages':[{'content':json.dumps({'question':'改成600元，日常账户','previous_question':'给同学转500元','previous_plan':{'kind':'clarification','tools':[]},'clarification_context':['给同学转500元']})}]})['content']
  self.assertEqual(value['tools'][0]['args']['amountMinor'],60000)
 def test_current_recipient_change_wins_over_older_clarification_fragment(self):
  from safebank_agent.models import MockModel
  value=MockModel().complete({'messages':[{'content':json.dumps({'question':'改给同事，从日常账户','previous_question':'给同学转500元','previous_plan':{'kind':'clarification','tools':[]},'clarification_context':['给同学转500元']})}]})['content']
  self.assertEqual(value['tools'][0]['args']['recipientQuery'],'同事')
