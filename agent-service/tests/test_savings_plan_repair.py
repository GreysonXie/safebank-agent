import json,unittest
from pathlib import Path
from safebank_agent.graph import build
from safebank_agent.contracts import ProtocolError

class SavingsPlanRepairTest(unittest.TestCase):
 def bad(self):
  p=json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'));p['nodes'][-1]['params']['sourceQuery']=''
  return {'kind':'savings_task','taskPlan':p}
 def context(self,responses):
  class Context:
   def __init__(self):self.calls=[]
   def model(self,state,node,sequence,payload):self.calls.append(payload);return {'content':responses.pop(0)}
  return Context()
 def test_missing_source_plan_can_repair_to_specific_question_without_tool(self):
  context=self.context([self.bad(),{'kind':'clarification','tools':[],'question':'请说明从哪个付款账户转出。'}])
  output=build(None,context).nodes['plan'].invoke({'request':{'text':'将核验节省存旅游基金，未指定付款账户','modelRequestLimit':3}})
  self.assertEqual(output['plan']['kind'],'clarification');self.assertEqual(output['plan']['tools'],[]);self.assertEqual(len(context.calls),2)
  self.assertEqual(json.loads(context.calls[1]['messages'][-1]['content'])['validation_error'],'INVALID_TASK_PLAN')
 def test_repeat_invalid_plan_stops_after_one_repair(self):
  context=self.context([self.bad(),self.bad()])
  with self.assertRaisesRegex(ProtocolError,'INVALID_TASK_PLAN'):build(None,context).nodes['plan'].invoke({'request':{'text':'把节省存起来','modelRequestLimit':3}})
  self.assertEqual(len(context.calls),2)
 def test_request_limit_prevents_unbudgeted_repair(self):
  context=self.context([self.bad()])
  with self.assertRaisesRegex(ProtocolError,'INVALID_TASK_PLAN'):build(None,context).nodes['plan'].invoke({'request':{'text':'把节省存起来','modelRequestLimit':1}})
  self.assertEqual(len(context.calls),1)
 def test_repair_cannot_fill_missing_source_by_copying_recipient(self):
  guessed=self.bad();guessed['taskPlan']['nodes'][-1]['params']['sourceQuery']='储蓄账户'
  context=self.context([self.bad(),guessed])
  with self.assertRaisesRegex(ProtocolError,'TASK_PARAMETERS_REQUIRED'):build(None,context).nodes['plan'].invoke({'request':{'text':'未指定付款账户，把节省存到储蓄账户','modelRequestLimit':3}})
  self.assertEqual(len(context.calls),2)
 def test_repair_receives_specific_missing_field_without_question_template(self):
  context=self.context([self.bad(),{'kind':'clarification','tools':[],'question':'您打算从哪个账户转出？'}])
  build(None,context).nodes['plan'].invoke({'request':{'text':'把节省存起来','modelRequestLimit':3}})
  self.assertEqual(json.loads(context.calls[1]['messages'][-1]['content'])['missing_task_fields'],['sourceQuery'])
