import json,uuid
import httpx
from .test_graph_runtime import GraphRuntimeTest,Bank
from safebank_agent.models import DeepSeek
from safebank_agent.runtime import Runtime

class ProviderReceiptTest(GraphRuntimeTest):
 def provider_run(self,incomplete=False):
  self.runtime.close();calls=[];records=[];bank=Bank()
  bank.reserve=lambda body,token:{'reservationId':uuid.uuid4().hex}
  bank.record=lambda body,token:records.append(body)
  replies=[('{"kind":"general","tools":[]}','stop'),('{"kind":','length' if incomplete else 'stop'),('{"kind":"general","answer":"请到业务页核对确认范围。"}','stop')]
  def reply(request):
   calls.append(request);raw,finish=replies[len(calls)-1]
   return httpx.Response(200,json={'id':'response-'+str(len(calls)),'model':'mock-http','usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12},'choices':[{'finish_reason':finish,'message':{'content':raw}}]})
  model=DeepSeek('test-only','requested',client=httpx.Client(transport=httpx.MockTransport(reply)),max_sends=3)
  self.runtime=Runtime(self.store,self.config,bank,model)
  run=self.runtime.accept(dict(self.request,modelRequestLimit=3));self.runtime.execute(run['id'])
  with self.store.transaction() as c:
   c.execute("SELECT state,result_json FROM agent_effect_receipts WHERE origin_run_id=%s AND node_name='draft'",(run['id'],));effect=c.fetchone()
   c.execute('SELECT state,prompt_tokens,completion_tokens FROM agent_model_requests WHERE run_id=%s',(run['id'],));usage=c.fetchall()
  return self.store.get(run['id']),effect,usage,calls,records
 def test_received_invalid_json_is_saved_and_only_rewritten_once(self):
  run,effect,usage,calls,records=self.provider_run()
  self.assertEqual(run['state'],'RESULT_READY',run.get('error_code'));self.assertEqual(len(calls),3)
  self.assertEqual(effect['state'],'SUCCEEDED');saved=json.loads(effect['result_json']);self.assertEqual(saved['raw_content'],'{"kind":');self.assertEqual(saved['response_error'],'MODEL_CONTENT_NOT_JSON')
  self.assertEqual(len(records),3);self.assertTrue(all(x['usage']['total_tokens']==12 for x in records))
  self.assertTrue(all(x['state']=='SUCCEEDED' and x['prompt_tokens']==10 and x['completion_tokens']==2 for x in usage))
 def test_received_incomplete_reply_is_saved_but_stops_without_rewrite(self):
  run,effect,usage,calls,records=self.provider_run(True)
  self.assertEqual(run['state'],'RECONCILIATION_REQUIRED');self.assertEqual(run['error_code'],'MODEL_INCOMPLETE')
  self.assertEqual(len(calls),2);self.assertEqual(effect['state'],'SUCCEEDED');self.assertEqual(len(records),2)
  self.assertEqual(json.loads(effect['result_json'])['response_error'],'MODEL_INCOMPLETE')
  self.assertTrue(all(x['state']=='SUCCEEDED' and x['prompt_tokens']==10 for x in usage))
