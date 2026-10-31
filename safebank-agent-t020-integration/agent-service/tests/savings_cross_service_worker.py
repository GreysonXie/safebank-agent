"""Opt-in free fixture: actual Java callbacks and a separate Python/MySQL runtime.

Input (including the short-lived grant) travels on stdin, never argv or stdout.
"""
import json
import sys
from pathlib import Path
from safebank_agent.bank_client import BankClient
from safebank_agent.runtime import Runtime
from safebank_agent.store import Store
from safebank_agent.models import MockModel
from .test_savings_task_runtime import SavingsTaskRuntimeTest
from .test_graph_runtime import Model


class LostResponseBank(BankClient):
 def __init__(self,url,key,lose):
  super().__init__(url,key);self.lose=lose;self.calls=[];self.replays=[]
 def tool(self,body,token):
  self.calls.append(body['operationKey'])
  receipt=super().tool(body,token);self.replays.append(receipt.get('replay',False))
  if self.lose:
   self.lose=False
   raise SystemExit('Java committed; local effect receipt not saved')
  return receipt


class CountingMockModel(MockModel):
 def __init__(self):self.calls=0
 def complete(self,payload):
  self.calls+=1;return super().complete(payload)


def main():
 input=json.loads(sys.stdin.buffer.read().decode('utf-8'))
 SavingsTaskRuntimeTest.setUpClass()
 runtime=None;bank=None
 try:
  config=SavingsTaskRuntimeTest.config;store=Store(config);model=CountingMockModel() if input.get('offlineFixture') else Model()
  plan=json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'))
  plan['analysisMonth']='2026-09';plan['nodes'][-1]['params']['recipientQuery']='旅游基金'
  accepted=input['confirmed']
  if not input.get('offlineFixture'):model.responses=[{'kind':'savings_task','taskPlan':plan},
   {'kind':'analysis','answer':'任务当前阶段为{{savings_task.phase}}。尚未执行，请在操作卡逐项确认。'} if accepted else
   {'kind':'general','answer':'尚未确认查询范围，没有生成任务。请在代扣页面确认查询。'}]
  bank=LostResponseBank(input['url'],'x'*64,accepted)
  runtime=Runtime(store,config,bank,model);request=input['request'];run=runtime.accept(request)
  crashed=False
  try:runtime.execute(run['id'])
  except SystemExit:
   crashed=True;runtime.close()
   with store.transaction() as c:c.execute('UPDATE agent_runs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=%s',(run['id'],))
   runtime=Runtime(store,config,bank,model);runtime.accept(request);runtime.execute(run['id'])
  saved=store.get(run['id']);checkpoint=runtime.graph.get_state({'configurable':{'thread_id':saved['thread_id']}})
  print(json.dumps({'state':saved['state'],'errorCode':saved.get('error_code'),'result':json.loads(saved['result_json']) if saved.get('result_json') else None,
   'crashed':crashed,'toolAttempts':len(bank.calls),'uniqueOperationKeys':len(set(bank.calls)),
   'replays':bank.replays,'modelCalls':model.calls,'workflowId':checkpoint.values.get('workflow_id')}))
 finally:
  if runtime:runtime.close()
  if bank:bank.http.close()
  SavingsTaskRuntimeTest.tearDownClass()


if __name__=='__main__':main()
