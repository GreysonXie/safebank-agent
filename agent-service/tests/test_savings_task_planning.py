import json,unittest
from pathlib import Path
from safebank_agent.graph import planning
from safebank_agent.contracts import ProtocolError
class SavingsTaskPlanningTest(unittest.TestCase):
 def plan(self):return json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'))
 def test_savings_graph_compiles_to_single_controlled_prepare(self):
  p=self.plan();actual=planning({'kind':'savings_task','taskPlan':p})
  self.assertEqual(actual['tools'],[{'tool':'savings_task_prepare','args':{'taskPlan':p}}])
 def test_foreign_identity_or_money_is_rejected(self):
  p=self.plan();p['nodes'][-1]['params']['amountMinor']=100
  with self.assertRaises(ProtocolError):planning({'kind':'savings_task','taskPlan':p})
 def test_status_is_readonly_and_requires_uuid(self):
  p={'kind':'analysis','tools':[{'tool':'savings_task_status','args':{'workflowId':'00000000-0000-0000-0000-000000000001'}}]}
  self.assertEqual(planning(p),p)
  p['tools'][0]['args']['userId']='u2'
  with self.assertRaises(ProtocolError):planning(p)
 def test_task_identity_only_comes_from_successful_bank_receipt(self):
  from safebank_agent.task_plan import workflow_from_receipts
  identifier='00000000-0000-0000-0000-000000000001'
  self.assertEqual(workflow_from_receipts([{'tool':'savings_task_prepare','data':{'id':identifier}}]),identifier)
  self.assertIsNone(workflow_from_receipts([{'tool':'savings_task_prepare','data':{'id':identifier,'code':'DENIED'}}]))
  self.assertIsNone(workflow_from_receipts([{'tool':'statement','data':{'id':identifier}}]))
