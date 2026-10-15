"""Free fixed-response faults against actual MySQL/LangGraph checkpoints."""
import json
import uuid
from pathlib import Path
from . import test_store_effects as storage_support
from . import test_graph_runtime as support


class TaskBank(support.Bank):
 def __init__(self):
  super().__init__();self.identifier=str(uuid.uuid4());self.requests=[]
 def tool(self,request,token):
  self.calls+=1;self.requests.append(request)
  return {'tool':request['tool'],'data':{'id':self.identifier},'evidenceId':uuid.uuid4().hex,'facts':{'savings_task.phase':{'display':'待确认','kind':'TEXT','source':'agent_savings_task'}}}


class InspectModel(support.Model):
 def __init__(self):super().__init__();self.contexts=[]
 def complete(self,payload):
  self.contexts.append(json.loads(payload['messages'][-1]['content']))
  return super().complete(payload)


class SavingsTaskRuntimeTest(storage_support.RuntimeSchemaTest):
 @classmethod
 def setUpClass(cls):
  super().setUpClass()
  import hashlib,pymysql
  from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
  with pymysql.connect(**dict(cls.config,cursorclass=pymysql.cursors.DictCursor),autocommit=True) as c:PyMySQLSaver(c).setup()
  checksum=hashlib.sha256((Path(__file__).resolve().parents[1]/'migrations/001_runtime.sql').read_text().encode()).hexdigest()
  with pymysql.connect(**cls.config,autocommit=True) as c,c.cursor() as s:s.execute('INSERT INTO agent_schema_migrations VALUES(1,%s,UTC_TIMESTAMP(6))',(checksum,))
 def setUp(self):
  support.GraphRuntimeTest.setUp(self)
  self.runtime.close()
  from safebank_agent.runtime import Runtime
  self.bank=TaskBank();self.model=InspectModel()
  self.runtime=Runtime(self.store,self.config,self.bank,self.model)
  self.request['text']='撤销音乐和视频代扣，把核验节省按月存入储蓄账户'
 def tearDown(self):self.runtime.close()
 def proposal(self):
  return {'kind':'savings_task','taskPlan':json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'))}
 def restart(self):
  self.runtime.close()
  from safebank_agent.runtime import Runtime
  self.runtime=Runtime(self.store,self.config,self.bank,self.model)
 def follow(self,version=2):
  return self.runtime.accept(dict(self.request,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=version,text='看看任务进度'))
 def test_completed_checkpoint_recovery_does_not_register_twice(self):
  self.model.responses=[self.proposal(),{'kind':'analysis','answer':'计划已生成，请在操作卡逐项核对确认，尚未执行。'}]
  run=self.runtime.accept(self.request);original=self.store.result
  def crash(*args):raise SystemExit('after checkpoint before result')
  self.store.result=crash
  with self.assertRaises(SystemExit):self.runtime.execute(run['id'])
  self.store.result=original;self.restart()
  with self.store.transaction() as c:c.execute('UPDATE agent_runs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=%s',(run['id'],))
  self.runtime.accept(dict(self.request,grant='renewed'));self.runtime.execute(run['id'])
  saved=self.store.get(run['id'])
  self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
  self.assertEqual(self.bank.calls,1);self.assertEqual(self.model.calls,2)
  checkpoint=self.runtime.graph.get_state({'configurable':{'thread_id':saved['thread_id']}})
  self.assertEqual(checkpoint.values['workflow_id'],self.bank.identifier)
 def test_restart_status_uses_original_task_without_prepare(self):
  self.model.responses=[self.proposal(),{'kind':'analysis','answer':'计划已生成，尚未执行。'}]
  first=self.runtime.accept(self.request);self.runtime.execute(first['id']);old=self.store.get(first['id'])
  self.assertEqual(old['state'],'RESULT_READY',old.get('error_code'));self.store.ack(first['id'],2,old['result_hash']);self.restart()
  self.model.responses=[{'kind':'analysis','tools':[{'tool':'savings_task_status','args':{'workflowId':self.bank.identifier}}]},{'kind':'analysis','answer':'请在操作卡查看待确认步骤。'}]
  run=self.follow();self.runtime.execute(run['id'])
  self.assertEqual(self.store.get(run['id'])['state'],'RESULT_READY')
  self.assertEqual(self.model.contexts[2]['previous_workflow_id'],self.bank.identifier)
  self.assertEqual([r['tool'] for r in self.bank.requests],['savings_task_prepare','savings_task_status'])
 def test_post_prepare_clarification_resume_keeps_task_identity(self):
  self.model.responses=[self.proposal(),{'kind':'clarification','answer':'计划待确认，你想先查看哪项操作？'}]
  first=self.runtime.accept(self.request);self.runtime.execute(first['id']);old=self.store.get(first['id'])
  self.assertEqual(old['state'],'RESULT_READY',old.get('error_code'));self.assertIsNotNone(old['interrupt_id'])
  self.store.ack(first['id'],2,old['result_hash']);self.restart()
  self.model.responses=[{'kind':'analysis','tools':[{'tool':'savings_task_status','args':{'workflowId':self.bank.identifier}}]},{'kind':'analysis','answer':'请在操作卡查看待确认步骤。'}]
  run=self.follow();self.runtime.execute(run['id'])
  self.assertEqual(self.store.get(run['id'])['state'],'RESULT_READY')
  self.assertEqual(self.model.contexts[2]['previous_workflow_id'],self.bank.identifier)
  self.assertEqual(self.bank.calls,2)
 def test_lost_prepare_receipt_reuses_original_operation_key(self):
  self.model.responses=[self.proposal(),{'kind':'analysis','answer':'计划待确认，尚未执行。'}]
  original=self.bank.tool;accepted={};attempts=[]
  def lost(request,token):
   key=request['operationKey'];attempts.append(key)
   if key not in accepted:
    accepted[key]=original(request,token)
    raise SystemExit('bank committed before local receipt')
   return accepted[key]
  self.bank.tool=lost;run=self.runtime.accept(self.request)
  with self.assertRaises(SystemExit):self.runtime.execute(run['id'])
  self.restart()
  with self.store.transaction() as c:c.execute('UPDATE agent_runs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=%s',(run['id'],))
  self.runtime.accept(dict(self.request,grant='renewed'));self.runtime.execute(run['id'])
  self.assertEqual(self.store.get(run['id'])['state'],'RESULT_READY')
  self.assertEqual(len(attempts),2);self.assertEqual(len(set(attempts)),1)
  self.assertEqual(self.bank.calls,1);self.assertEqual(self.model.calls,2)
 def test_rejected_prepare_does_not_remember_unverified_identifier(self):
  original=self.bank.tool
  def reject(request,token):
   receipt=original(request,token);receipt['data']['code']='QUERY_CONFIRMATION_REQUIRED';receipt['facts']={};return receipt
  self.bank.tool=reject
  self.model.responses=[self.proposal(),{'kind':'clarification','answer':'请先在代扣页面确认查询范围。'}]
  run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
  self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
  state=self.runtime.graph.get_state({'configurable':{'thread_id':saved['thread_id']}})
  self.assertIsNone(state.values['workflow_id']);self.assertIsNotNone(saved['interrupt_id'])
