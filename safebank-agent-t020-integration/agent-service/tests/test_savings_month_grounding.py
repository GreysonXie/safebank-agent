"""Captured planning defect: optional schema month must not erase user intent."""
import copy,json,unittest
from pathlib import Path
from safebank_agent.graph import build

class SavingsMonthGroundingTest(unittest.TestCase):
 def proposal(self):
  return json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'))
 def run_plan(self,question,proposals,previous_question='',previous_plan=None):
  class Context:
   def __init__(self):self.payloads=[]
   def model(self,state,node,sequence,payload):
    self.payloads.append(payload);return {'content':{'kind':'savings_task','taskPlan':proposals.pop(0)}}
  context=Context();node=build(None,context).nodes['plan']
  result=node.invoke({'request':{'text':question,'modelRequestLimit':3},'previous_question':previous_question,'previous_plan':previous_plan or {}})
  return result,context.payloads
 def test_explicit_month_omission_requires_repair_not_java_default(self):
  bad=self.proposal();good=copy.deepcopy(bad);good['analysisMonth']='2026-09'
  result,payloads=self.run_plan('核对2026-09账单，首次2026-11-20，结束2027-10-20。',[bad,good])
  self.assertEqual(len(payloads),2)
  self.assertEqual(result['plan']['tools'][0]['args']['taskPlan']['analysisMonth'],'2026-09')
  self.assertEqual(json.loads(payloads[1]['messages'][-1]['content'])['validation_error'],'SAVINGS_ANALYSIS_MONTH_REQUIRED')
 def test_wrong_month_repaired_and_clarification_preserves_previous_explicit_month(self):
  bad=self.proposal();bad['analysisMonth']='2026-11';good=copy.deepcopy(bad);good['analysisMonth']='2026-09'
  result,payloads=self.run_plan('首次2026-11-20，结束2027-10-20。',[bad,good],'核对2026-09账单',{'kind':'clarification'})
  self.assertEqual(len(payloads),2);self.assertEqual(result['plan']['tools'][0]['args']['taskPlan']['analysisMonth'],'2026-09')
 def test_schedule_dates_are_not_analysis_month_and_new_task_does_not_reuse_old_month(self):
  result,payloads=self.run_plan('首次2026-11-20，结束2027-10-20。',[self.proposal()],'分析2026-09账单',{'kind':'analysis'})
  self.assertEqual(len(payloads),1);self.assertNotIn('analysisMonth',result['plan']['tools'][0]['args']['taskPlan'])
