import json,unittest
from pathlib import Path
from safebank_agent.graph import planning

class SavingsRealCaseContractTest(unittest.TestCase):
 def test_fixed_real_acceptance_cases_cover_creation_clarification_followup_and_status(self):
  cases=json.loads((Path(__file__).parent/'fixtures/savings_task_real_cases.json').read_text(encoding='utf-8'))
  self.assertEqual(len(cases),6)
  self.assertEqual(len({c['id'] for c in cases}),6)
  by={c['id']:c for c in cases}
  self.assertEqual(by['T02_followup']['previousCase'],'T02_missing')
  self.assertTrue(all(c.get('useVerifiedWorkflow') for c in cases if c['expected']=='savings_task_status'))
 def test_successful_fixture_oracle_preserves_business_params(self):
  proposal=json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'))
  proposal['analysisMonth']='2026-09';proposal['nodes'][-1]['params']['recipientQuery']='旅游基金'
  actual=planning({'kind':'savings_task','taskPlan':proposal})
  self.assertEqual(actual['tools'][0]['tool'],'savings_task_prepare')
  self.assertNotIn('amountMinor',actual['tools'][0]['args']['taskPlan']['nodes'][-1]['params'])
