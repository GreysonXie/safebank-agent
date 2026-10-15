import copy,json,unittest
from unittest.mock import patch
from safebank_agent.graph import build
from safebank_agent.contracts import ProtocolError

class Context:
 def __init__(self,*answers):self.answers=iter(answers);self.payloads=[]
 def model(self,state,node,index,payload):self.payloads.append(json.loads(payload['messages'][-1]['content']));return {'content':next(self.answers)}

class ClarificationContextTest(unittest.TestCase):
 def test_explicit_revoke_preview_cannot_be_satisfied_by_only_reading_mandates(self):
  context=Context({'kind':'analysis','tools':[{'tool':'mandate_receipt','args':{}}]},{'kind':'analysis','tools':[{'tool':'mandate_receipt','args':{}},{'tool':'revoke_preview','args':{'merchantQuery':'音乐服务'}}]})
  result=build(None,context).nodes['plan'].invoke({'request':{'text':'只生成音乐服务撤销预览，先不执行','modelRequestLimit':3}})
  self.assertEqual(result['plan']['tools'][-1]['tool'],'revoke_preview');self.assertEqual(result['plan_repair'],1)
  self.assertEqual(context.payloads[1]['validation_error'],'REVOKE_PREVIEW_REQUIRED')
 def test_revoke_preview_requirement_excludes_consultation_and_negative_requests(self):
  from safebank_agent.conversation import requires_revoke_preview
  for text in ('只生成音乐服务撤销预览，先不执行','请准备视频服务的撤销预览'):self.assertTrue(requires_revoke_preview(text))
  for text in ('不要生成音乐服务撤销预览','别准备撤销预览','不要给我生成音乐服务撤销预览','我不想生成音乐服务撤销预览','我不打算生成音乐服务撤销预览','我不准备生成音乐服务撤销预览','无需帮我准备视频服务的撤销预览','可以生成音乐服务撤销预览吗？','生成撤销预览是什么意思？','先不办'):self.assertFalse(requires_revoke_preview(text),text)
 def test_negative_preview_intent_does_not_force_repair_to_preparation(self):
  for text in ('不要给我生成音乐服务撤销预览','我不想生成音乐服务撤销预览'):
   context=Context({'kind':'general','tools':[]})
   result=build(None,context).nodes['plan'].invoke({'request':{'text':text,'modelRequestLimit':2}})
   self.assertEqual(result['plan'],{'kind':'general','tools':[]});self.assertEqual(result['plan_repair'],0)
 def test_captured_numeric_clarification_is_repaired_before_bank_validation(self):
  context=Context({'kind':'clarification','tools':[],'question':'好的，金额500元已记下，请说明付款账户。'},{'kind':'clarification','tools':[],'question':'请说明付款账户。'})
  result=build(None,context).nodes['plan'].invoke({'request':{'text':'500元','modelRequestLimit':3}})
  self.assertEqual(result['plan']['question'],'请说明付款账户。');self.assertEqual(result['plan_repair'],1)
  self.assertEqual(context.payloads[1]['validation_error'],'CLARIFICATION_NUMERIC_LITERAL')
 def test_numeric_clarification_with_no_repair_budget_cannot_reach_wait(self):
  context=Context({'kind':'clarification','tools':[],'question':'金额５００元，使用哪个账户？'})
  with self.assertRaisesRegex(ProtocolError,'CLARIFICATION_NUMERIC_LITERAL'):build(None,context).nodes['plan'].invoke({'request':{'text':'500元','modelRequestLimit':2}})
 def test_amount_amendment_after_statement_requires_target_without_tools(self):
  previous={'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]}
  for tool in ({'tool':'statement','args':{'month':'2026-09'}},{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':70000}}):
   with self.subTest(tool=tool['tool']):
    context=Context({'kind':'analysis','tools':[tool]})
    with self.assertRaisesRegex(ProtocolError,'AMENDMENT_TARGET_REQUIRED'):build(None,context).nodes['plan'].invoke({'request':{'text':'再改成700','modelRequestLimit':2},'previous_plan':previous})
 def test_amount_amendment_target_has_one_bounded_repair(self):
  context=Context({'kind':'general','tools':[]},{'kind':'clarification','tools':[],'question':'你希望修改哪项操作的金额？'})
  result=build(None,context).nodes['plan'].invoke({'request':{'text':'改为700元','modelRequestLimit':3}})
  self.assertTrue(context.payloads[0]['amendment_target_required']);self.assertEqual(result['plan']['kind'],'clarification')
  self.assertEqual(result['plan_repair'],1);self.assertEqual(context.payloads[1]['validation_error'],'AMENDMENT_TARGET_REQUIRED')
 def test_amount_amendment_scope_preserves_active_transfer_and_explicit_requests(self):
  from safebank_agent.conversation import requires_amendment_target
  statement={'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]}
  transfer={'kind':'analysis','tools':[{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':50000}}]}
  for text in ('再改成700','改为７００元。','换成700块'):
   self.assertTrue(requires_amendment_target(text,statement,[],'分析2026-09账单'))
   self.assertFalse(requires_amendment_target(text,transfer,[],'给同学转钱'))
  for text in ('改成2026-08','把账单改成2026-08','从日常账户给同学转700元','改成700元，先解释风险','最大一笔是什么？'):
   self.assertFalse(requires_amendment_target(text,statement,[],'分析2026-09账单'))
  self.assertFalse(requires_amendment_target('改成700',{'kind':'clarification','tools':[]},['给同学转钱','500元'],'500元'))
  self.assertTrue(requires_amendment_target('改成700',statement,['给同学转钱'],'分析2026-09账单'))
 def test_finite_mock_asks_amendment_target_instead_of_inheriting_statement_month(self):
  from safebank_agent.models import MockModel
  value=MockModel().complete({'messages':[{'content':json.dumps({'question':'再改成700','previous_plan':{'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]}})}]})['content']
  self.assertEqual(value['kind'],'clarification');self.assertEqual(value['tools'],[]);self.assertIn('修改',value['question'])
 def test_active_transfer_amount_variants_update_the_new_amount(self):
  from safebank_agent.models import MockModel
  previous={'kind':'analysis','tools':[{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'同学','amountMinor':50000}}]}
  for text in ('再改成700','改为700元','换成700块','改为７００元'):
   with self.subTest(text=text):
    value=MockModel().complete({'messages':[{'content':json.dumps({'question':text,'previous_plan':previous})}]})['content']
    self.assertEqual(value['tools'][0]['args']['amountMinor'],70000)
 def test_fraud_hold_warning_takes_priority_over_missing_amendment_target(self):
  context=Context({'kind':'general','tools':[]})
  result=build(None,context).nodes['plan'].invoke({'request':{'text':'再改成700','modelRequestLimit':2},'transfer_safety_hold':True})
  self.assertTrue(result['safety_blocked']);self.assertEqual(result['plan']['kind'],'general')
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
