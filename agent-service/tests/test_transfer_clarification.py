import copy,json,unittest
from safebank_agent.graph import build

class TransferClarificationTest(unittest.TestCase):
 def setUp(self):
  self.facts={'recipients_latest.0.note':{'display':'〔同学〕','kind':'TEXT'},'recipients_latest.1.note':{'display':'〔同事〕','kind':'TEXT'}}
  self.state={'request':{'text':'日常账户，转100','modelRequestLimit':3,'turnId':'test-turn','expectedVersion':1},'plan':{'kind':'analysis','tools':[{'tool':'transfer_preview','args':{'sourceQuery':'日常账户','recipientQuery':'张三','amountMinor':10000}}]},'receipts':[{'tool':'transfer_preview','evidenceId':'test-evidence','data':{'code':'RECIPIENT_CLARIFICATION_REQUIRED','sourceResolved':True,'recipientResolution':'AMBIGUOUS','candidatesTruncated':False},'facts':self.facts}],'answer':{'kind':'clarification','answer':'请补充收款人的姓名或备注；另外也请说明付款账户。'},'repair':0}
 def validate(self,state=None):
  class Context:
   def validate(self,*args):pass
  return build(None,Context()).nodes['validate'].invoke(state or self.state)
 def test_captured_redundant_source_question_is_rewritten(self):
  result=self.validate();self.assertEqual(result.get('repair'),1);self.assertIn('付款账户',result['validation_error'])
 def test_captured_repeated_name_question_must_offer_distinctions(self):
  state=copy.deepcopy(self.state);state['answer']['answer']='请补充收款人姓名或备注。付款账户和金额你已说明，无需重复提供。'
  result=self.validate(state);self.assertEqual(result.get('repair'),1);self.assertIn('候选',result['validation_error'])
 def test_grounded_options_do_not_repeat_source_question(self):
  state=copy.deepcopy(self.state);state['answer']['answer']='付款账户已明确。你指的是备注{{recipients_latest.0.note}}还是{{recipients_latest.1.note}}的收款人？尚未生成预览。'
  self.assertTrue(self.validate(state)['validated'])
 def test_unresolved_source_still_allows_source_question(self):
  state=copy.deepcopy(self.state);state['receipts'][0]['data']['sourceResolved']=False;state['answer']['answer']='请说明付款账户；收款人是备注{{recipients_latest.0.note}}还是{{recipients_latest.1.note}}的那位？'
  self.assertTrue(self.validate(state)['validated'])
 def test_draft_receives_only_missing_fields_and_current_candidate_refs(self):
  class Context:
   def model(self,state,name,index,payload):self.payload=json.loads(payload['messages'][-1]['content']);return {'content':state['answer']}
  context=Context();build(None,context).nodes['draft'].invoke(self.state)
  contract=context.payload['answer_contract'];self.assertEqual(contract['allowed_kinds'],['clarification']);self.assertTrue(contract['transfer_clarification']['source_resolved'])
  self.assertEqual(set(contract['transfer_clarification']['candidate_distinction_refs']),{'{{recipients_latest.0.note}}','{{recipients_latest.1.note}}'})

 def test_mock_uses_bank_candidate_refs_instead_of_repeating_name_question(self):
  from safebank_agent.models import MockModel
  context={'question':'日常账户，转100','facts':self.facts,'tool_notices':[{'code':'RECIPIENT_CLARIFICATION_REQUIRED','message':'请补充姓名或备注'}],'answer_contract':{'transfer_clarification':{'candidate_distinction_refs':['{{recipients_latest.0.note}}','{{recipients_latest.1.note}}']}}}
  result=MockModel().complete({'messages':[{'role':'user','content':json.dumps(context,ensure_ascii=False)}]})['content']
  self.assertEqual(result['kind'],'clarification');self.assertIn('{{recipients_latest.0.note}}',result['answer']);self.assertIn('{{recipients_latest.1.note}}',result['answer'])

 def test_repair_does_not_bypass_per_turn_budget_or_repeat_rewrite(self):
  from safebank_agent.contracts import ProtocolError
  state=copy.deepcopy(self.state);state['request']['modelRequestLimit']=2
  with self.assertRaisesRegex(ProtocolError,'ANSWER_REPAIR_BUDGET_EXHAUSTED'):self.validate(state)
  # 两次重写用完后不再重写：降级为一次可用的澄清，不执行任何操作，也不把会话堵死
  state=copy.deepcopy(self.state);state['repair']=2
  fallback=self.validate(state)
  self.assertTrue(fallback['validated']);self.assertEqual(fallback['answer']['kind'],'clarification')
  self.assertIn('没有执行任何操作',fallback['answer']['answer'])
 def test_same_notes_require_distinct_bank_labels(self):
  from safebank_agent.graph import transfer_clarification
  facts={'recipients_latest.0.note':{'display':'〔未提供备注〕','kind':'TEXT'},'recipients_latest.1.note':{'display':'〔未提供备注〕','kind':'TEXT'},'recipients_latest.0.label':{'display':'张三 TEST-3271','kind':'TEXT'},'recipients_latest.1.label':{'display':'张三 TEST-8862','kind':'TEXT'}}
  contract=transfer_clarification(self.state,facts)
  self.assertEqual(set(contract['candidate_distinction_refs']),{'{{recipients_latest.0.label}}','{{recipients_latest.1.label}}'})

 def test_partially_indistinguishable_candidates_cannot_disappear(self):
  from safebank_agent.graph import transfer_clarification
  state=copy.deepcopy(self.state);state['receipts'][0]['facts']={'recipients_latest.0.note':{'display':'〔同学〕','kind':'TEXT'},'recipients_latest.1.note':{'display':'〔同事〕','kind':'TEXT'},'recipients_latest.2.note':{'display':'〔同事〕','kind':'TEXT'}}
  contract=transfer_clarification(state,state['receipts'][0]['facts']);self.assertTrue(contract['has_indistinguishable_candidates'])
  state['answer']['answer']='你指的是{{recipients_latest.0.note}}对应的收款人吗？'
  self.assertEqual(self.validate(state).get('repair'),1)
  state['answer']['answer']='可见备注{{recipients_latest.0.note}}，但另有候选现有信息无法区分，请先核对并补充备注。尚未生成预览。'
  self.assertTrue(self.validate(state)['validated'])
