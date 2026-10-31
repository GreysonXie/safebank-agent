import copy,json,unittest
from pathlib import Path
from safebank_agent.graph import compact_facts,build
from safebank_agent.models import MockModel

class MandateFocusTest(unittest.TestCase):
 def setUp(self):self.fixture=json.loads((Path(__file__).parent/'fixtures/20261011_mandate_catalog.json').read_text(encoding='utf-8'))
 def test_captured_question_keeps_only_named_rows_and_original_source(self):
  facts=self.fixture['facts'];before=copy.deepcopy(facts);selected=compact_facts(facts,self.fixture['question'])
  self.assertIn('mandates_latest.2.amount_minor',selected);self.assertIn('mandates_latest.2.contract_status',selected)
  self.assertIn('mandates_latest.observation.queriedAt',selected)
  self.assertFalse(any(k.startswith('mandates_latest.3.') for k in selected))
  self.assertFalse(any(k.startswith('mandates_summary.') for k in selected))
  self.assertFalse(any(k.startswith('e0.') for k in selected))
  self.assertEqual(selected['mandates_latest.2.amount_minor']['source'],facts['mandates_latest.2.amount_minor']['source']);self.assertEqual(facts,before)
 def test_overview_aggregate_or_unresolved_pronoun_preserves_business_scope(self):
  for question in ('所有代扣有哪些？','明确月付合计多少？','季度安全服务在全部月付合计里怎么算？','这个还能取消吗？'):
   with self.subTest(question=question):
    selected=compact_facts(self.fixture['facts'],question)
    self.assertIn('mandates_latest.3.merchant',selected);self.assertIn('mandates_summary.known_monthly_total_minor',selected)
 def test_explicit_comparison_keeps_both_named_services(self):
  facts={'mandates_latest.0.merchant':{'display':'〔季度安全服务（合成）〕'},'mandates_latest.1.merchant':{'display':'〔视频服务（合成）〕'},'mandates_latest.2.merchant':{'display':'〔音乐服务（合成）〕'}}
  selected=compact_facts(facts,'比较季度安全服务和视频服务的周期')
  self.assertIn('mandates_latest.0.merchant',selected);self.assertIn('mandates_latest.1.merchant',selected);self.assertNotIn('mandates_latest.2.merchant',selected)
 def test_longer_exact_name_and_duplicate_name_do_not_pick_wrong_service(self):
  facts={'mandates_latest.0.merchant':{'display':'安全服务'},'mandates_latest.1.merchant':{'display':'季度安全服务'},'mandates_latest.2.merchant':{'display':'季度安全服务'}}
  selected=compact_facts(facts,'季度安全服务能取消吗？')
  self.assertNotIn('mandates_latest.0.merchant',selected);self.assertIn('mandates_latest.1.merchant',selected);self.assertIn('mandates_latest.2.merchant',selected)
 def test_focus_does_not_remove_other_bank_tools_or_fabricate_fact_ids(self):
  facts=dict(self.fixture['facts']);facts['statement_2026_09.expense_minor']={'display':'5,369.33元','source':'bank','kind':'MONEY'}
  selected=compact_facts(facts,'季度安全服务和账单支出怎么核对？')
  self.assertIn('statement_2026_09.expense_minor',selected);self.assertTrue(set(selected)<=set(facts))
 def test_legacy_alias_is_kept_when_no_stable_copy_is_present(self):
  facts={'e0.amount_minor':{'display':'12.00元','source':'legacy-only','kind':'MONEY'}}
  self.assertIn('e0.amount_minor',compact_facts(facts,'说明这笔金额'))
 def test_draft_graph_passes_only_focused_receipt_facts_to_model(self):
  class Context:
   def model(self,state,name,index,payload):self.payload=payload;return MockModel().complete(payload)
  context=Context();state={'request':{'text':self.fixture['question']},'receipts':[{'facts':self.fixture['facts'],'data':[]}]}
  answer=build(None,context).nodes['draft'].invoke(state)['answer']['answer'];payload=json.loads(context.payload['messages'][-1]['content'])
  self.assertNotIn('mandates_latest.3.merchant',payload['facts']);self.assertIn('按季度',answer);self.assertIn('合同',answer)
  self.assertNotIn('QUARTERLY',answer);self.assertNotIn('{{mandates_latest.3.',answer)

 def test_repair_uses_the_same_focused_catalog(self):
  class Context:
   def model(self,state,name,index,payload):self.name=name;self.payload=payload;return MockModel().complete(payload)
  context=Context();state={'request':{'text':self.fixture['question']},'receipts':[{'facts':self.fixture['facts'],'data':[]}],'repair':1,'validation_error':'Please rewrite only the answer contract.'}
  build(None,context).nodes['draft'].invoke(state)
  self.assertEqual(context.name,'repair');facts=json.loads(context.payload['messages'][-1]['content'])['facts']
  self.assertIn('mandates_latest.2.amount_minor',facts);self.assertNotIn('mandates_latest.3.merchant',facts)
 def test_stable_alias_copy_is_removed_but_unmatched_legacy_source_is_not(self):
  facts={'mandates_latest.2.merchant':{'display':'季度安全服务','source':'list_mandates；历史快照：已确认','kind':'TEXT'},'mandates_latest.3.merchant':{'display':'音乐服务','source':'list_mandates；历史快照：已确认','kind':'TEXT'},'e0.3.merchant':{'display':'音乐服务','source':'list_mandates','kind':'TEXT'},'e1.3.merchant':{'display':'音乐服务','source':'different-bank-tool','kind':'TEXT'}}
  selected=compact_facts(facts,'季度安全服务怎么收费？')
  self.assertNotIn('e0.3.merchant',selected);self.assertIn('e1.3.merchant',selected)

 def test_named_service_share_of_total_retains_global_summary(self):
  selected=compact_facts(self.fixture['facts'],'季度安全服务占每月代扣总支出多少？')
  self.assertIn('mandates_summary.known_monthly_total_minor',selected)
  self.assertIn('mandates_latest.3.merchant',selected)

 def test_other_services_and_joint_amount_keep_full_scope(self):
  for question in ('季度安全服务跟其余代扣相比收费周期有什么不同？','季度安全服务和别的代扣一起每月扣多少钱？'):
   with self.subTest(question=question):
    selected=compact_facts(self.fixture['facts'],question)
    self.assertIn('mandates_latest.3.merchant',selected)
    self.assertIn('mandates_summary.known_monthly_total_minor',selected)
 def test_short_full_name_is_recognized(self):
  facts={'mandates_latest.0.merchant':{'display':'优酷'},'mandates_latest.1.merchant':{'display':'腾讯视频'}}
  self.assertEqual(set(compact_facts(facts,'优酷能取消吗？')),{'mandates_latest.0.merchant'})
 def test_unknown_extended_name_does_not_select_substring(self):
  facts={'mandates_latest.0.merchant':{'display':'ABC'},'mandates_latest.1.merchant':{'display':'XYZ'}}
  self.assertEqual(set(compact_facts(facts,'ABCDEF能取消吗？')),set(facts))
  for question in ('季度安全服务升级版能取消吗？','超级季度安全服务能取消吗？','季度安全服务升级版和视频服务能取消吗？'):
   with self.subTest(question=question):
    selected=compact_facts(self.fixture['facts'],question)
    self.assertIn('mandates_latest.3.merchant',selected)

 def test_unasked_renewal_date_is_omitted_but_explicit_question_keeps_it(self):
  facts=self.fixture['facts']
  self.assertNotIn('mandates_latest.2.renewal_date',compact_facts(facts,self.fixture['question']))
  for question in ('季度安全服务下次何时续费？','季度安全服务下次什么时候扣款？','季度安全服务的收费日期是什么？'):
   with self.subTest(question=question):
    self.assertIn('mandates_latest.2.renewal_date',compact_facts(facts,question))
 def test_negative_scope_instruction_does_not_expand_to_other_rows_or_dates(self):
  selected=compact_facts(self.fixture['facts'],'只围绕季度安全服务解释收费周期和银行撤销边界，别主动讲其他服务或续费日期。')
  self.assertIn('mandates_latest.2.amount_minor',selected)
  self.assertNotIn('mandates_latest.3.merchant',selected);self.assertNotIn('mandates_latest.2.renewal_date',selected)
  broad=compact_facts(self.fixture['facts'],'不要只说季度安全服务，其他服务也请比较收费周期。')
  self.assertIn('mandates_latest.3.merchant',broad)
 def test_named_revoked_service_with_bank_side_question_keeps_its_row_only(self):
  selected=compact_facts(self.fixture['facts'],'已撤销服务银行侧是什么状态？能保证以后商户不收费吗？')
  self.assertIn('mandates_latest.3.state_label',selected);self.assertNotIn('mandates_latest.2.merchant',selected)
 def test_draft_groups_monthly_membership_by_status_and_cycle(self):
  class Context:
   def model(self,state,name,index,payload):self.payload=payload;return MockModel().complete(payload)
  context=Context();state={'request':{'text':'季度安全服务跟其余代扣相比，明确月付合计多少？'},'receipts':[{'facts':self.fixture['facts'],'data':[]}]}
  build(None,context).nodes['draft'].invoke(state)
  payload=json.loads(context.payload['messages'][-1]['content']);rows={row['rowId']:row for row in payload['mandate_rows']}
  self.assertEqual({key for key,row in rows.items() if row['monthlyTotalMembership']=='included'},{'mandates_latest.1','mandates_latest.4'})
  self.assertEqual(rows['mandates_latest.3']['monthlyTotalMembership'],'excluded')
  self.assertEqual(rows['mandates_latest.2']['monthlyTotalMembership'],'excluded')
  self.assertEqual(rows['mandates_latest.0']['monthlyTotalMembership'],'excluded')
  self.assertEqual(rows['mandates_latest.3']['fields']['merchant'],'{{mandates_latest.3.merchant}}')
