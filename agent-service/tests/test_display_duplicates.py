import copy,unittest
from safebank_agent.answers import clean_display_duplicates
from safebank_agent.graph import answer_catalog,build

class DisplayDuplicatesTest(unittest.TestCase):
 def setUp(self):
  self.facts={'mandates_latest.4.merchant':{'display':'〔视频服务（合成）〕','kind':'TEXT'},'mandates_latest.2.merchant':{'display':'〔季度安全服务（合成）〕','kind':'TEXT'},'mandates_latest.3.state_label':{'display':'银行侧授权已撤销','kind':'TEXT'}}
 def test_captured_literal_names_are_not_rendered_twice(self):
  original={'kind':'analysis','answer':'视频服务〔视频服务（合成）〕是按月；季度安全服务〔季度安全服务（合成）〕是约定按季度收费。'}
  before=copy.deepcopy(original);result=clean_display_duplicates(original,self.facts)
  self.assertEqual(result['answer'],'〔视频服务（合成）〕是按月；〔季度安全服务（合成）〕是约定按季度收费。');self.assertEqual(original,before)
 def test_duplicate_literal_plus_reference_retains_reference_and_negation(self):
  value={'kind':'analysis','answer':'〔视频服务（合成）〕{{mandates_latest.4.merchant}}，不能据此保证银行侧授权已撤销{{mandates_latest.3.state_label}}，金额{{a.amount_minor}}。'}
  result=clean_display_duplicates(value,self.facts)
  self.assertEqual(result['answer'],'{{mandates_latest.4.merchant}}，不能据此保证{{mandates_latest.3.state_label}}，金额{{a.amount_minor}}。')
 def test_no_deletion_of_different_names_qualifiers_or_nonadjacent_text(self):
  for text in ('超级视频服务〔视频服务（合成）〕','视频服务不是〔视频服务（合成）〕','视频服务与{{mandates_latest.4.merchant}}','未撤销{{mandates_latest.3.state_label}}','〔音乐服务（合成）〕{{mandates_latest.4.merchant}}','{{other.merchant}}视频服务'):
   value={'kind':'analysis','answer':text};self.assertEqual(clean_display_duplicates(value,self.facts),value)
 def test_duplicate_short_names_do_not_choose_between_objects(self):
  facts={**self.facts,'mandates_latest.5.merchant':{'display':'〔视频服务（合成）〕','kind':'TEXT'}}
  value={'kind':'analysis','answer':'视频服务〔视频服务（合成）〕'};self.assertEqual(clean_display_duplicates(value,facts),value)
 def test_rules_and_usage_availability_require_relevant_question(self):
  facts={'statement_2026_09.anomaly_rule':{'display':'整段门槛规则','kind':'TEXT'},'statement_2026_09.usage_data_available':{'display':'false','kind':'TEXT'},'statement_2026_09.expense_minor':{'display':'5369.33元','kind':'MONEY'}}
  selected,_=answer_catalog(facts,'只核对最大的那笔支出，哪些信息需要我确认？')
  self.assertNotIn('statement_2026_09.anomaly_rule',selected);self.assertNotIn('statement_2026_09.usage_data_available',selected);self.assertIn('statement_2026_09.expense_minor',selected)
  selected,_=answer_catalog(facts,'大额筛选门槛是什么，能确认服务使用频率吗？')
  self.assertIn('statement_2026_09.anomaly_rule',selected);self.assertIn('statement_2026_09.usage_data_available',selected)
 def test_graph_audits_cleanup_on_draft_and_repair(self):
  class Context:
   def __init__(self):self.events=[]
   def model(self,*args):return {'content':{'kind':'analysis','answer':'视频服务〔视频服务（合成）〕是按月收费。'}}
   def note(self,state,event):self.events.append(event)
  for repair in (0,1):
   context=Context();result=build(None,context).nodes['draft'].invoke({'request':{'text':'视频服务怎么收费'},'receipts':[{'facts':self.facts,'data':[]}],'repair':repair})
   self.assertEqual(result['answer']['answer'],'〔视频服务（合成）〕是按月收费。');self.assertIn('ANSWER_DISPLAY_DUPLICATE_REMOVED',context.events)
 def test_runtime_can_persist_cleanup_event_and_still_guards_lease(self):
  from safebank_agent.runtime import Runtime
  from unittest.mock import MagicMock
  from types import SimpleNamespace
  runtime=Runtime.__new__(Runtime);runtime.store=MagicMock();lease=SimpleNamespace(run_id='test-run');runtime.lease=lambda state:lease
  runtime.note({},'ANSWER_DISPLAY_DUPLICATE_REMOVED')
  runtime.store.guard.assert_called_once();self.assertIs(runtime.store.guard.call_args.args[1],lease)
  self.assertEqual(runtime.store.event.call_args.args[-1],'ANSWER_DISPLAY_DUPLICATE_REMOVED')
