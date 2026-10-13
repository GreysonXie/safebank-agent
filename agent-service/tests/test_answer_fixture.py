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
