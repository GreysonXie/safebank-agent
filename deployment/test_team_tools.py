"""Free deployment checks: no Docker engine, provider or database required."""
import ast,copy,importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from subprocess import CompletedProcess

HERE=Path(__file__).resolve().parent
def module(name):
 spec=importlib.util.spec_from_file_location('team_'+name,HERE/(name+'.py'))
 value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

class DoctorTest(unittest.TestCase):
 def setUp(self):
  self.doctor=module('doctor')
  self.config={'services':{'mysql':{'environment':{'MYSQL_ROOT_PASSWORD':'local-secret-'+'x'*32}},'app':{'environment':{'SAFEBANK_MODEL_MODE':'mock','DEEPSEEK_API_KEY':'','SAFEBANK_MODEL_SMOKE_CAP':'20'},'ports':[{'target':8134,'published':'18134','host_ip':'127.0.0.1'}]}}}
 def test_mock_settings_and_custom_port_are_valid(self):
  before=copy.deepcopy(self.config);checks,port=self.doctor.inspect_config(self.config)
  self.assertTrue(all(x['ok'] for x in checks));self.assertEqual(port,18134);self.assertEqual(before,self.config)
 def test_paid_mode_missing_key_and_invalid_cap_are_reported_without_secrets(self):
  env=self.config['services']['app']['environment'];env.update(SAFEBANK_MODEL_MODE='deepseek',SAFEBANK_MODEL_SMOKE_CAP='not-a-number')
  checks,_=self.doctor.inspect_config(self.config);self.assertFalse(all(x['ok'] for x in checks))
  self.assertTrue(any(x['code']=='MODEL_KEY' and not x['ok'] for x in checks));self.assertTrue(any(x['code']=='MODEL_CAP' and not x['ok'] for x in checks))
  self.assertNotIn(self.config['services']['mysql']['environment']['MYSQL_ROOT_PASSWORD'],json.dumps(checks))
 def test_public_web_or_database_port_is_rejected(self):
  self.config['services']['app']['ports'][0]['host_ip']='0.0.0.0';self.config['services']['mysql']['ports']=[{'published':'3306'}]
  checks,_=self.doctor.inspect_config(self.config)
  self.assertTrue(any(x['code']=='LOOPBACK_PORT' and not x['ok'] for x in checks));self.assertTrue(any(x['code']=='MYSQL_PRIVATE' and not x['ok'] for x in checks))
 def test_placeholder_password_and_invalid_mode_are_rejected(self):
  self.config['services']['mysql']['environment']['MYSQL_ROOT_PASSWORD']='REPLACE_WITH_LOCAL_RANDOM_PASSWORD'
  self.config['services']['app']['environment']['SAFEBANK_MODEL_MODE']='unknown'
  checks,_=self.doctor.inspect_config(self.config);self.assertFalse(all(x['ok'] for x in checks))
 def test_report_is_safe_and_does_not_contain_effective_environment(self):
  secret='fixture-key-never-print';self.config['services']['app']['environment'].update(SAFEBANK_MODEL_MODE='deepseek',DEEPSEEK_API_KEY=secret)
  checks,_=self.doctor.inspect_config(self.config);self.assertTrue(all(x['ok'] for x in checks));self.assertNotIn(secret,json.dumps(checks))
 def test_real_preflight_path_only_runs_readonly_commands_and_uses_effective_config(self):
  commands=[]
  def run(args,**kwargs):
   commands.append(args)
   result=json.dumps(self.config) if 'config' in args else 'linux' if 'info' in args else '2.39'
   return CompletedProcess(args,0,result,'')
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);(root/'.env').write_text('not interpreted by doctor',encoding='utf-8')
   with patch.object(self.doctor.shutil,'which',return_value='docker'),patch.object(self.doctor.subprocess,'run',side_effect=run):checks=self.doctor.inspect_project(root,running=True)
  self.assertTrue(all(c['ok'] for c in checks));self.assertEqual(commands,[['docker','compose','version','--short'],['docker','info','--format','{{.OSType}}'],['docker','compose','config','--format','json']])
  self.assertNotIn(self.config['services']['mysql']['environment']['MYSQL_ROOT_PASSWORD'],json.dumps(checks))
 def test_docker_errors_never_disclose_private_output(self):
  secret='fixture-private-docker-output'
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);(root/'.env').touch()
   with patch.object(self.doctor.shutil,'which',return_value='docker'),patch.object(self.doctor.subprocess,'run',return_value=CompletedProcess([],1,secret,secret)):
    checks=self.doctor.inspect_project(root)
  self.assertFalse(all(c['ok'] for c in checks));self.assertNotIn(secret,json.dumps(checks))
 def test_missing_docker_never_attempts_subprocess(self):
  with tempfile.TemporaryDirectory() as folder,patch.object(self.doctor.shutil,'which',return_value=None),patch.object(self.doctor.subprocess,'run') as run:
   checks=self.doctor.inspect_project(Path(folder))
   run.assert_not_called();self.assertFalse(all(c['ok'] for c in checks))

class SmokeTest(unittest.TestCase):
 def setUp(self):
  # The old script does network I/O at import. Fail before importing it.
  tree=ast.parse((HERE/'smoke.py').read_text(encoding='utf-8-sig'))
  self.assertTrue(any(isinstance(x,ast.FunctionDef) and x.name=='main' for x in tree.body),'smoke needs an import-safe CLI entrypoint')
  self.smoke=module('smoke')
 def test_paid_mode_is_rejected_before_any_post(self):
  calls=[]
  def request(path,body=None,csrf=None):calls.append((path,body));return {'mode':'LANGGRAPH_MODEL','csrf':'secret'}
  with self.assertRaisesRegex(self.smoke.SmokeFailure,'PAID_MODE'):self.smoke.run_smoke(request,wait_seconds=0)
  self.assertEqual(calls,[('/api/session',None)])
 def test_query_and_replay_reuse_exact_request_without_executing_actions(self):
  posts=[];answer={'text':'合成账单回答','actions':[],'mode':'LANGGRAPH_MOCK','conversationId':'cid','contextVersion':2}
  def request(path,body=None,csrf=None):
   if path=='/api/session':return {'mode':'LANGGRAPH_MOCK','csrf':'secret'}
   if path=='/api/conversations':return {'conversationId':'cid','version':1}
   self.assertEqual(path,'/api/chat');posts.append(copy.deepcopy(body));return copy.deepcopy(answer)
  report=self.smoke.run_smoke(request,wait_seconds=0)
  self.assertEqual(len(posts),2);self.assertEqual(posts[0],posts[1]);self.assertTrue(report['ok']);self.assertNotIn('secret',json.dumps(report))
 def test_invalid_replay_or_unexpected_action_fails(self):
  for failure in ('replay','action'):
   calls=[]
   def request(path,body=None,csrf=None):
    if path=='/api/session':return {'mode':'LANGGRAPH_MOCK','csrf':'secret'}
    if path=='/api/conversations':return {'conversationId':'cid','version':1}
    calls.append(body);return {'text':'回答','mode':'LANGGRAPH_MOCK','actions':[{'id':'unexpected'}] if failure=='action' else [],'conversationId':'cid','contextVersion':3 if failure=='replay' and len(calls)>1 else 2}
   with self.assertRaises(self.smoke.SmokeFailure):self.smoke.run_smoke(request,wait_seconds=0)
 def test_only_explicit_loopback_url_is_allowed(self):
  self.assertEqual(self.smoke.validate_base_url('http://127.0.0.1:8136/'),'http://127.0.0.1:8136')
  for url in ('https://example.com','http://127.0.0.1:8136/path','http://token@127.0.0.1:8136','http://127.0.0.1:8136/?key=secret'):
   with self.assertRaises(self.smoke.SmokeFailure):self.smoke.validate_base_url(url)

if __name__=='__main__':unittest.main()
