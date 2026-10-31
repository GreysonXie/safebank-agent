import unittest
import httpx

class ModelHTTPTest(unittest.TestCase):
 def test_openai_gateway_uses_standard_json_contract_without_deepseek_parameters(self):
  import json
  from safebank_agent.models import OpenAICompatible
  calls=[]
  def reply(request):
   calls.append(request)
   return httpx.Response(200,json={'model':'gpt-6.1-sol','usage':{'total_tokens':12},'choices':[{'finish_reason':'stop','message':{'content':'{"kind":"general","answer":"hello"}'}}]})
  with httpx.Client(transport=httpx.MockTransport(reply)) as client:
   model=OpenAICompatible('fixture-key','gpt-6.1-sol','https://gateway.example/v1/',client=client,max_sends=1)
   result=model.complete({'messages':[{'role':'user','content':'Return JSON.'}],'max_tokens':100})
   from safebank_agent.contracts import ProtocolError
   with self.assertRaisesRegex(ProtocolError,'MODEL_STAGE_CAP'):model.complete({'messages':[],'max_tokens':100})
  self.assertEqual(model.mode,'openai');self.assertEqual(len(calls),1)
  self.assertEqual(str(calls[0].url),'https://gateway.example/v1/chat/completions')
  self.assertEqual(calls[0].headers['Authorization'],'Bearer fixture-key')
  body=json.loads(calls[0].content)
  self.assertNotIn('thinking',body);self.assertEqual(body['model'],'gpt-6.1-sol')
  self.assertEqual(body['response_format'],{'type':'json_object'});self.assertFalse(body['stream'])
  self.assertEqual(result['content']['answer'],'hello');self.assertEqual(result['usage']['total_tokens'],12)
 def test_only_unambiguous_rejections_have_terminal_failure_type(self):
  from safebank_agent.models import DeepSeek
  from safebank_agent.contracts import ModelRequestRejected,ProtocolError
  for status in (400,401,403,404,405,413,415,422,408,429,500,502,503):
   with self.subTest(status=status):
    client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(status)))
    try:
     with self.assertRaises(ProtocolError) as error:DeepSeek('test-key','requested',client=client).complete({'messages':[],'max_tokens':100})
     self.assertEqual(isinstance(error.exception,ModelRequestRejected),status in ModelRequestRejected.STATUSES)
    finally:client.close()
 def test_keepalive_data_does_not_allow_unbounded_model_wait(self):
  from safebank_agent.models import DeepSeek
  from safebank_agent.contracts import ProtocolError
  from unittest.mock import patch
  model=DeepSeek('test-key','requested',client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,content=b' '))))
  with patch('safebank_agent.models.monotonic',side_effect=[0,91]):
   with self.assertRaisesRegex(ProtocolError,'MODEL_DEADLINE_EXCEEDED'):model.complete({'messages':[],'max_tokens':100})
  self.assertEqual(model.sends,1)
 def test_http_error_records_status_without_response_body_or_retry(self):
  from safebank_agent.models import DeepSeek
  from safebank_agent.contracts import ProtocolError
  calls=[]
  def reply(request):calls.append(request);return httpx.Response(400,json={'error':{'message':'private provider text'}})
  model=DeepSeek('test-key','requested',client=httpx.Client(transport=httpx.MockTransport(reply)))
  with self.assertRaisesRegex(ProtocolError,'MODEL_REQUEST_REJECTED_400'):model.complete({'messages':[],'max_tokens':100})
  self.assertEqual(model.last_failure,'HTTP_400');self.assertEqual(len(calls),1)
 def test_default_model_timeout_allows_slow_response_but_bounds_connection(self):
  from safebank_agent.models import DeepSeek
  model=DeepSeek('test-key','requested')
  try:
   self.assertEqual(model.client.timeout.read,60)
   self.assertEqual(model.client.timeout.connect,5)
  finally:model.client.close()
 def test_incomplete_reply_preserves_usage_without_retry(self):
  from safebank_agent.models import DeepSeek
  calls=[]
  def reply(request):
   calls.append(request);return httpx.Response(200,json={'id':'incomplete','usage':{'total_tokens':12},'choices':[{'finish_reason':'length','message':{'content':'{"kind":"analysis"}'}}]})
  model=DeepSeek('test-key','requested',client=httpx.Client(transport=httpx.MockTransport(reply)))
  result=model.complete({'messages':[],'max_tokens':100})
  self.assertEqual(result['response_error'],'MODEL_INCOMPLETE');self.assertEqual(result['usage']['total_tokens'],12)
  self.assertEqual(result['raw_content'],'{"kind":"analysis"}');self.assertEqual(len(calls),1)
 def test_invalid_content_json_preserves_raw_reply_and_usage(self):
  from safebank_agent.models import DeepSeek
  raw='{"kind":'
  client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json={'id':'bad-json','model':'actual','usage':{'total_tokens':12},'choices':[{'finish_reason':'stop','message':{'content':raw}}]})))
  result=DeepSeek('test-key','requested',client=client).complete({'messages':[],'max_tokens':100})
  self.assertIsNone(result['content']);self.assertEqual(result['response_error'],'MODEL_CONTENT_NOT_JSON')
  self.assertEqual(result['raw_content'],raw);self.assertEqual(result['usage']['total_tokens'],12)
 def test_extreme_content_keeps_receipt_instead_of_becoming_transport_unknown(self):
  from safebank_agent.models import DeepSeek
  for raw in ('['*1200+'0'+']'*1200,'9'*5000,'['*64+'0'+']'*64,'{"answer":1e999}','{"answer":NaN}',r'{"answer":"\ud800"}'):
   with self.subTest(contentSize=len(raw)):
    client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json={'usage':{'total_tokens':12},'choices':[{'finish_reason':'stop','message':{'content':raw}}]})))
    reply=DeepSeek('test-key','requested',client=client).complete({'messages':[],'max_tokens':100})
    self.assertEqual(reply['response_error'],'MODEL_CONTENT_NOT_JSON');self.assertEqual(reply['raw_content'],raw)
    self.assertEqual(reply['usage']['total_tokens'],12)
 def test_transport_timeout_remains_uncertain_and_is_not_retried(self):
  from safebank_agent.models import DeepSeek
  from safebank_agent.contracts import ProtocolError
  calls=[]
  def timeout(request):calls.append(request);raise httpx.ReadTimeout('synthetic timeout',request=request)
  model=DeepSeek('test-key','requested',client=httpx.Client(transport=httpx.MockTransport(timeout)))
  with self.assertRaises(ProtocolError):model.complete({'messages':[],'max_tokens':100})
  self.assertEqual(len(calls),1)
  self.assertEqual(model.last_failure,'ReadTimeout')
 def test_structured_reply_preserves_actual_usage(self):
  from safebank_agent.models import DeepSeek
  client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json={'id':'r1','model':'actual','usage':{'prompt_tokens':10,'completion_tokens':2,'total_tokens':12},'choices':[{'finish_reason':'stop','message':{'content':'{"kind":"general","answer":"hello"}'}}]})))
  reply=DeepSeek('test-key','requested',client=client).complete({'messages':[],'max_tokens':100})
  self.assertEqual(reply['usage']['total_tokens'],12);self.assertEqual(reply['model'],'actual')
 def test_planning_disallows_injected_tool_and_identity(self):
  from safebank_agent.graph import planning
  from safebank_agent.contracts import ProtocolError
  with self.assertRaises(ProtocolError):planning({'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09','userId':'u2'}}]})
  with self.assertRaises(ProtocolError):planning({'kind':'general','tools':[],'grant':'forged'})
 def test_fact_context_is_bounded_and_keeps_focus(self):
  from safebank_agent.graph import compact_facts
  facts={f'statement_2026_09.transactions.{i}.merchant':{'display':'很长的商户备注'*100,'source':'bank'} for i in range(1000)}
  facts['statement_2026_09.expense_minor']={'display':'5369.33元','source':'bank'}
  facts['statement_2026_09.anomaly_candidates.0.spending_minor']={'display':'2600元','source':'bank'}
  compact=compact_facts(facts,'核对大额支出')
  from safebank_agent.contracts import canonical
  self.assertLessEqual(len(canonical(compact)),18000)
  self.assertIn('statement_2026_09.expense_minor',compact)
  self.assertIn('statement_2026_09.anomaly_candidates.0.spending_minor',compact)
