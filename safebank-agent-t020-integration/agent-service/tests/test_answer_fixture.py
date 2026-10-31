"""Replay the captured provider output through MySQL graph + actual Java validator."""
import json,os,subprocess,uuid
from pathlib import Path
import httpx
from .test_graph_runtime import Model
from .test_store_effects import RuntimeSchemaTest
from safebank_agent.contracts import bank_hash,ProtocolError

class CapturedAnswerTest(RuntimeSchemaTest):
 @classmethod
 def setUpClass(cls):
  if not os.getenv('SAFEBANK_TEST_JAVA_CLASSPATH'):raise RuntimeError('Actual Java oracle classpath required; do not skip captured regression')
  super().setUpClass()
  import pymysql,hashlib
  from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
  with pymysql.connect(**dict(cls.config,cursorclass=pymysql.cursors.DictCursor),autocommit=True) as c:PyMySQLSaver(c).setup()
  checksum=hashlib.sha256((Path(__file__).resolve().parents[1]/'migrations/001_runtime.sql').read_text().encode()).hexdigest()
  with pymysql.connect(**cls.config,autocommit=True) as c,c.cursor() as s:s.execute('INSERT INTO agent_schema_migrations VALUES(1,%s,UTC_TIMESTAMP(6))',(checksum,))
 def test_original_draft_becomes_verified_without_third_model_call(self):
  from safebank_agent.store import Store
  from safebank_agent.runtime import Runtime
  folder=Path(__file__).resolve().parent/'fixtures';fixture=json.loads((folder/'20261011_answer_failure.json').read_text(encoding='utf-8'))
  bank=JavaBank(folder);model=Model();model.responses=[{'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]},fixture['answer']]
  store=Store(self.config);runtime=Runtime(store,self.config,bank,model)
  try:
   request=dict(userId='u1',conversationId=uuid.uuid4().hex,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=1,text='分析2026-09账单，哪些支出最值得关注？',grant='fixture-only',modelRequestLimit=2)
   run=runtime.accept(request);runtime.execute(run['id']);saved=store.get(run['id'])
   self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
   self.assertEqual(model.calls,2);self.assertNotIn('八成',bank.rendered);self.assertIn('2,500.00',bank.rendered);self.assertIn('2,000.00',bank.rendered);self.assertIn('已省略',bank.rendered)
   answer=json.loads(saved['result_json'])['answer'];self.assertEqual(set(answer),{'kind','answer'});self.assertLessEqual(bank.validations,4)
   with store.transaction() as c:
    c.execute("SELECT COUNT(*) n FROM agent_run_events WHERE run_id=%s AND event_code='ANSWER_NUMERIC_SENTENCE_OMITTED'",(run['id'],));self.assertLessEqual(c.fetchone()['n'],2)
   if os.getenv('SAFEBANK_CAPTURED_TEST_RESULT'):
    Path(os.environ['SAFEBANK_CAPTURED_TEST_RESULT']).write_text(json.dumps({'runState':saved['state'],'modelFixtureReplays':model.calls,'providerHTTPCalls':0,'javaValidations':bank.validations,'answer':bank.rendered,'actualJavaGrounding':True},ensure_ascii=False,indent=2),encoding='utf-8')
  finally:runtime.close();bank.close()

 def test_statement_largest_followup_uses_current_java_facts_across_three_turns(self):
  from safebank_agent.store import Store
  from safebank_agent.runtime import Runtime
  bank=JavaBank(Path(__file__).resolve().parent/'fixtures');model=Model();payloads=[]
  original=model.complete
  def capture(payload):payloads.append(json.loads(payload['messages'][-1]['content']));return original(payload)
  model.complete=capture
  plan={'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]}
  prefix='statement_2026_09.analysis.largest_expenses.0'
  model.responses=[plan,{'kind':'analysis','answer':'账单支出为{{statement_2026_09.expense_minor}}。'},
   plan,{'kind':'analysis','answer':'最大支出对应{{'+prefix+'.merchant}}，支出{{'+prefix+'.spending_minor}}，需核对用途凭证。'},
   plan,{'kind':'analysis','answer':'这笔对应{{'+prefix+'.merchant}}，不能仅凭商户名称确定实际用途，需核对原订单和凭证。'}]
  store=Store(self.config);runtime=Runtime(store,self.config,bank,model);cid=uuid.uuid4().hex
  questions=['分析2026-09账单','最大一笔是什么','这笔能确定用途吗'];rendered=[]
  try:
   for version,question in enumerate(questions,1):
    request=dict(userId='u1',conversationId=cid,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=version,text=question,grant='fixture-only',modelRequestLimit=2)
    run=runtime.accept(request);runtime.execute(run['id']);saved=store.get(run['id'])
    self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'));rendered.append(bank.rendered)
    store.ack(run['id'],version+1,saved['result_hash'])
   self.assertEqual(model.calls,6);self.assertEqual(len(model.responses),0)
   self.assertIn('2,500.00',rendered[1]);self.assertIn('租金',rendered[1]);self.assertNotIn('退款',rendered[1])
   self.assertIn('不能仅凭商户名称确定实际用途',rendered[2]);self.assertIn('租金',rendered[2])
   self.assertEqual(payloads[3]['previous_question'],questions[0]);self.assertEqual(payloads[5]['previous_question'],questions[1])
   self.assertTrue(any(row['rowId']==prefix for row in payloads[3]['transaction_rows']))
  finally:runtime.close();bank.close()


 def test_statement_five_turns_preserve_refund_boundary_and_summary_shape(self):
  from safebank_agent.store import Store
  from safebank_agent.runtime import Runtime
  bank=JavaBank(Path(__file__).resolve().parent/'fixtures');model=Model();payloads=[]
  original=model.complete
  def capture(payload):payloads.append(json.loads(payload['messages'][-1]['content']));return original(payload)
  model.complete=capture
  plan={'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]}
  rank='statement_2026_09.analysis.largest_expenses.0';refund='statement_2026_09.transactions.1'
  answers=[
   '账单支出为{{statement_2026_09.expense_minor}}，入账为{{statement_2026_09.income_minor}}；两者口径不同。',
   '最大支出对应{{'+rank+'.merchant}}，支出{{'+rank+'.spending_minor}}，需核对用途凭证。',
   '这笔对应{{'+rank+'.merchant}}，不能仅凭商户名称确定实际用途，需核对原订单和凭证。',
   '当前记录中，{{'+refund+'.merchant}}入账{{'+refund+'.amount_minor}}，分类为{{'+refund+'.category}}。退款增加入账，不抵减支出{{statement_2026_09.expense_minor}}；账单未提供与原订单的关联。',
   '- 大额核对：{{'+rank+'.merchant}}支出{{'+rank+'.spending_minor}}，金额不能证明异常，需核对凭证。\n- 口径区分：支出{{statement_2026_09.expense_minor}}不因退款直接抵减，订单关联仍需凭证。\n- 用途证据：账单记录不能证明服务使用频率或合同状态。']
  for answer in answers:model.responses.extend([plan,{'kind':'analysis','answer':answer}])
  questions=['分析2026-09账单','最大一笔支出是什么？','这笔能确定用途吗？','哪些退款能确认，能直接抵扣支出吗？','只说最值得关注的三点，不要逐笔复述']
  store=Store(self.config);runtime=Runtime(store,self.config,bank,model);cid=uuid.uuid4().hex;rendered=[]
  try:
   for version,question in enumerate(questions,1):
    run=runtime.accept(dict(userId='u1',conversationId=cid,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=version,text=question,grant='fixture-only',modelRequestLimit=2));runtime.execute(run['id']);saved=store.get(run['id'])
    self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'));rendered.append(bank.rendered);store.ack(run['id'],version+1,saved['result_hash'])
   self.assertEqual(model.calls,10);self.assertFalse(model.responses)
   self.assertIn('租金',rendered[1]);self.assertNotIn('退款',rendered[1]);self.assertIn('不能仅凭商户名称',rendered[2])
   self.assertIn('3,200.00',rendered[3]);self.assertIn('不抵减支出',rendered[3]);self.assertNotIn('设备维修',rendered[3])
   self.assertEqual(len(rendered[4].splitlines()),3);self.assertNotIn('3,200.00',rendered[4])
   self.assertTrue(any(row['rowId']==refund for row in payloads[7]['transaction_rows']))
   self.assertEqual([p['previous_question'] for p in payloads[3::2]],questions[:-1])
   self.assertIs(payloads[-1]['answer_contract']['transaction_catalog_complete'],False)
  finally:runtime.close();bank.close()

class JavaBank:
 def __init__(self,folder,fixture_name="20261011_answer_failure.json"):
  java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.getenv('JAVA_HOME') else 'java'
  self.process=subprocess.Popen([java,'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',os.environ['SAFEBANK_TEST_JAVA_CLASSPATH'],str(folder/'GroundingOracle.java'),str(folder/fixture_name)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
  self.facts=json.loads(self.process.stdout.readline())['facts'];self.rendered='';self.validations=0
  from safebank_agent.bank_client import BankClient
  self.client=BankClient('http://127.0.0.1:8134','x'*64);self.client.http.close();self.client.http=httpx.Client(transport=httpx.MockTransport(self.response))
 def tool(self,request,token):return {'evidenceId':'captured-statement','facts':self.facts}
 def response(self,request):
  body=json.loads(request.content);self.process.stdin.write(json.dumps(body['answer'],ensure_ascii=False)+'\n');self.process.stdin.flush()
  verdict=json.loads(self.process.stdout.readline());self.validations+=1
  if not verdict['ok']:return httpx.Response(400,json=verdict)
  self.rendered=verdict['text'];return httpx.Response(200,json={'text':self.rendered,'resultHash':bank_hash('agent_result',body)})
 def validate(self,value,token):return self.client.validate(value,token)
 def close(self):
  self.client.http.close();self.process.stdin.close()
  try:self.process.wait(timeout=5)
  except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)
  self.process.stdout.close();self.process.stderr.close()
