import json,os,re,unittest,uuid
from pathlib import Path
import pymysql
class RuntimeSchemaTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.admin=json.loads(os.environ['SAFEBANK_AGENT_TEST_DB_CONFIG']);cls.name='safebank_v07_test_agent_'+uuid.uuid4().hex
  with pymysql.connect(**cls.admin,autocommit=True) as c,c.cursor() as s:s.execute('CREATE DATABASE `'+cls.name+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin')
  cls.config=dict(cls.admin,database=cls.name)
  ddl=Path(__file__).resolve().parents[1]/'migrations/001_runtime.sql'
  if ddl.exists():
   with pymysql.connect(**cls.config,autocommit=True) as c,c.cursor() as s:
    for statement in re.sub(r'^--.*$', '', ddl.read_text(),flags=re.M).split(';'):
     if statement.strip():s.execute(statement)
 @classmethod
 def tearDownClass(cls):
  assert re.fullmatch(r'safebank_v07_test_agent_[a-f0-9]{32}',cls.name)
  with pymysql.connect(**cls.admin,autocommit=True) as c,c.cursor() as s:s.execute('DROP DATABASE `'+cls.name+'`')
 def test_five_application_tables_have_owned_effect_protocol(self):
  with pymysql.connect(**self.config) as c,c.cursor() as s:
   s.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('agent_thread_bindings','agent_runs','agent_effect_receipts','agent_model_requests','agent_run_events')")
   self.assertEqual(s.fetchone()[0],5,'Five versioned runtime tables must exist')

class StoreEffectsTest(RuntimeSchemaTest):
 def setUp(self):
  from safebank_agent.store import Store
  self.store=Store(self.config)
  self.request=dict(userId='u1',conversationId=uuid.uuid4().hex,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=1,text='分析账单',grant='must-never-persist')
 def test_identity_and_token_projection(self):
  from safebank_agent.contracts import ProtocolError
  run=self.store.accept(self.request)
  self.assertNotIn('must-never-persist',run['request_json'])
  self.assertEqual(self.store.accept(dict(self.request,grant='renewed'))['id'],run['id'])
  with self.assertRaisesRegex(ProtocolError,'REQUEST_CONFLICT'):
   self.store.accept(dict(self.request,text='别的问题'))
  with self.assertRaisesRegex(ProtocolError,'REQUEST_CONFLICT'):
   self.store.accept(dict(self.request,userId='u2'))
 def test_ack_is_immutable_and_java_advances_version(self):
  from safebank_agent.contracts import ProtocolError,bank_hash
  run=self.store.accept(self.request);lease=self.store.claim(run['id'],'worker')
  result=dict(turnId=run['id'],inputVersion=1,answer=dict(kind='clarification',answer='请提供账单月份。'),evidenceIds=[])
  self.store.result(lease,result,'clarification','interrupt-1')
  self.assertIsNone(self.store.get(run['id'])['output_version'])
  ack=self.store.ack(run['id'],2,bank_hash('agent_result',result));self.assertEqual(ack['state'],'NEEDS_CLARIFICATION')
  self.assertEqual(self.store.ack(run['id'],2,bank_hash('agent_result',result))['output_version'],2)
  with self.assertRaisesRegex(ProtocolError,'ACK_CONFLICT'):self.store.ack(run['id'],3,bank_hash('agent_result',result))
  follow=self.store.accept(dict(self.request,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=2,text='九月'))
  self.assertEqual(follow['thread_id'],run['thread_id'])
 def test_expired_worker_is_fenced(self):
  from safebank_agent.contracts import ProtocolError
  run=self.store.accept(self.request);old=self.store.claim(run['id'],'old')
  with self.store.transaction() as c:c.execute('UPDATE agent_runs SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=%s',(run['id'],))
  new=self.store.claim(run['id'],'new');self.assertGreater(new.revision,old.revision)
  with self.assertRaisesRegex(ProtocolError,'LEASE_LOST'):self.store.heartbeat(old)
  with self.assertRaisesRegex(ProtocolError,'LEASE_LOST'):self.store.result(old,dict(turnId=run['id'],inputVersion=1),'general')
 def test_effect_saved_before_checkpoint_reuses_after_restart(self):
  from safebank_agent.effects import Effects
  run=self.store.accept(self.request);lease=self.store.claim(run['id'],'worker');calls=[]
  first=Effects(self.store).call(lease,'plan',0,'model',dict(question='账单'),lambda key:(calls.append(key) or dict(content='已保存')))
  from safebank_agent.store import Store
  second=Effects(Store(self.config)).call(lease,'plan',0,'model',dict(question='账单'),lambda key:self.fail('saved effect reissued'))
  self.assertEqual(first,second);self.assertEqual(len(calls),1)
  from safebank_agent.contracts import ProtocolError
  with self.assertRaisesRegex(ProtocolError,'EFFECT_CONFLICT'):
   Effects(self.store).call(lease,'plan',0,'model',dict(question='不同输入'),lambda key:None)
 def test_sending_model_becomes_unknown_without_retry(self):
  from safebank_agent.effects import Effects
  from safebank_agent.contracts import ProtocolError,digest
  run=self.store.accept(self.request);lease=self.store.claim(run['id'],'worker')
  key=digest([run['id'],'plan',0,'model'])
  with self.store.transaction() as c:
   c.execute("INSERT INTO agent_effect_receipts(operation_key,origin_run_id,binding_id,origin_java_turn_id,node_name,logical_sequence,purpose,input_hash,state,lease_revision,created_at,updated_at) VALUES(%s,%s,%s,%s,'plan',0,'model',%s,'SENDING',%s,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",(key,run['id'],run['binding_id'],run['id'],digest({'question':'账单'}),lease.revision))
  with self.assertRaisesRegex(ProtocolError,'MODEL_EFFECT_UNKNOWN'):
   Effects(self.store).call(lease,'plan',0,'model',dict(question='账单'),lambda key:self.fail('unknown effect retried'))
  with self.store.transaction() as c:
   c.execute('SELECT state FROM agent_effect_receipts WHERE operation_key=%s',(key,));self.assertEqual(c.fetchone()['state'],'UNKNOWN')
