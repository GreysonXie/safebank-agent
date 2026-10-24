"""Read-only preflight; never print effective configuration or Docker stderr."""
import argparse,json,shutil,socket,subprocess,sys
from pathlib import Path
from urllib.parse import urlsplit

def check(code,ok,message):return {'code':code,'ok':bool(ok),'message':message}

def inspect_config(config):
 services=config.get('services',{});app=services.get('app',{});mysql=services.get('mysql',{})
 env=app.get('environment',{});password=mysql.get('environment',{}).get('MYSQL_ROOT_PASSWORD','')
 mode=env.get('SAFEBANK_MODEL_MODE');key=env.get('DEEPSEEK_API_KEY')
 if mode=='openai':key=env.get('SAFEBANK_MODEL_API_KEY')
 checks=[check('MYSQL_PASSWORD',isinstance(password,str) and len(password)>=20 and not password.startswith('REPLACE_'),'Generate a local password with deployment/configure.py; never share .env.'),
  check('MODEL_MODE',mode in ('mock','deepseek','openai'),'Effective model mode must be mock, deepseek or openai.'),
  check('MODEL_KEY',mode not in ('deepseek','openai') or isinstance(key,str) and bool(key.strip()),'Real model mode needs your own key; no provider connection is tested.')]
 if mode=='openai':
  try:
   endpoint=urlsplit(env.get('SAFEBANK_MODEL_URL',''));valid_url=endpoint.scheme=='https' and bool(endpoint.hostname) and endpoint.username is None and endpoint.password is None and not endpoint.query and not endpoint.fragment
   endpoint.port
  except (ValueError,TypeError):valid_url=False
  checks.append(check('MODEL_URL',valid_url,'OpenAI-compatible mode needs an HTTPS endpoint without credentials, query or fragment.'))
 try:valid_cap=1<=int(env.get('SAFEBANK_MODEL_SMOKE_CAP',''))<=100000
 except (TypeError,ValueError):valid_cap=False
 checks.append(check('MODEL_CAP',valid_cap,'Model request cap must be between 1 and 100000; it is not a money budget.'))
 ports=app.get('ports',[]);port=None;private=False
 if len(ports)==1 and isinstance(ports[0],dict):
  item=ports[0]
  try:
   port=int(item.get('published',''));private=item.get('host_ip')=='127.0.0.1' and int(item.get('target',0))==8134 and 1<=port<=65535
  except (ValueError,TypeError):pass
 checks.extend([check('LOOPBACK_PORT',private,'Publish only app port 8134 to a valid 127.0.0.1 host port.'),check('MYSQL_PRIVATE',not mysql.get('ports'),'Keep MySQL inside the Compose network.')])
 return checks,port if private else None

def inspect_project(root,running=False):
 checks=[check('PYTHON_HOST',sys.version_info>=(3,10),'Host Python 3.10+ is required; Agent dependencies run inside Docker.')]
 env_exists=(root/'.env').is_file()
 checks.append(check('LOCAL_ENV',env_exists,'Run python deployment/configure.py first; use .env, not .env.txt.'))
 if not shutil.which('docker'):
  checks.append(check('DOCKER_CLI',False,'Install Docker Desktop and use Linux containers.'));return checks
 checks.append(check('DOCKER_CLI',True,'Docker command is available.'))
 def command(args):
  try:
   result=subprocess.run(['docker',*args],cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
   return result.returncode==0,result.stdout
  except (OSError,subprocess.TimeoutExpired):return False,''
 ok,_=command(['compose','version','--short']);checks.append(check('COMPOSE_V2',ok,'Docker Compose v2 is required.'))
 linux,text=command(['info','--format','{{.OSType}}'])
 checks.append(check('DOCKER_LINUX',linux and text.strip()=='linux','Start Docker Desktop in Linux containers mode; follow its WSL2 setup if requested.'))
 if not env_exists or not ok:return checks
 valid,raw=command(['compose','config','--format','json'])
 try:config=json.loads(raw) if valid else None
 except (ValueError,TypeError):config=None
 checks.append(check('COMPOSE_CONFIG',isinstance(config,dict),'Effective Compose configuration must be valid; private output is hidden.'))
 if not isinstance(config,dict):return checks
 config_checks,port=inspect_config(config);checks.extend(config_checks)
 if port is not None and not running:
  try:
   with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as listener:listener.bind(('127.0.0.1',port))
   available=True
  except OSError:available=False
  checks.append(check('PORT_AVAILABLE',available,'Choose a free SAFEBANK_WEB_PORT; use --running for an existing deployment.'))
 return checks

def main(argv=None):
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--running',action='store_true',help='Skip the free-port requirement for an existing deployment.')
 parser.add_argument('--report',help='Optional sanitized report, for example .team-local/preflight.json.')
 args=parser.parse_args(argv)
 try:checks=inspect_project(Path(__file__).resolve().parents[1],args.running)
 except Exception:checks=[check('PREFLIGHT_ERROR',False,'Could not inspect deployment; no private error details printed.')]
 report={'ok':all(item['ok'] for item in checks),'checks':checks,'providerRequests':0,'containersChanged':False}
 for item in checks:print(('PASS' if item['ok'] else 'FAIL')+' '+item['code']+': '+item['message'])
 if args.report:
  path=Path(args.report);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 return 0 if report['ok'] else 1

if __name__=='__main__':raise SystemExit(main())
