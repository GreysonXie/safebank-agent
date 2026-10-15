"""Explicit finite demo samples, not natural-language model acceptance."""
import json
import unittest
from safebank_agent.models import MockModel
from safebank_agent.graph import planning

DEMO='演示储蓄任务：撤销音乐服务和视频服务，从日常账户把核验节省每月20日存入旅游基金，首次2026-11-20，结束2027-10-20，核对2026-09账单。'

class SavingsTaskMockTest(unittest.TestCase):
 def complete(self,**context):
  return MockModel().complete({'messages':[{'role':'user','content':json.dumps(context,ensure_ascii=False)}]})
 def test_explicit_demo_compiles_to_prepare_without_money_or_authorization(self):
  response=self.complete(question=DEMO)
  plan=planning(response['content'])
  self.assertEqual(response['model'],'offline-fixture')
  self.assertEqual([t['tool'] for t in plan['tools']],['savings_task_prepare'])
  self.assertEqual(plan['tools'][0]['args']['taskPlan']['analysisMonth'],'2026-09')
 def test_known_task_status_is_readonly(self):
  identifier='00000000-0000-0000-0000-000000000001'
  plan=planning(self.complete(question='查看储蓄任务进度',previous_workflow_id=identifier)['content'])
  self.assertEqual(plan['tools'],[{'tool':'savings_task_status','args':{'workflowId':identifier}}])
 def test_consultation_does_not_create_demo_task(self):
  value=self.complete(question='可以撤销音乐服务，把钱存起来吗？')['content']
  self.assertNotIn('savings_task_prepare',[t['tool'] for t in value.get('tools',[])])
 def test_answer_uses_server_phase_reference_without_execution_claim(self):
  response=self.complete(question='查看储蓄任务进度',facts={'savings_task_status.phase':{'display':'待确认','kind':'TEXT','source':'agent_savings_task_status'}})
  self.assertIn('{{savings_task_status.phase}}',response['content']['answer'])
  self.assertNotIn('已执行',response['content']['answer'])
