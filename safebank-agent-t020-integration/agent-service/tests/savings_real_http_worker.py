"""Explicit paid QA: production HTTP/runtime/model and Java callbacks, two sends max."""
import json,os,re,sys,threading,time
from pathlib import Path
import httpx
from safebank_agent.bank_client import BankClient
from safebank_agent.models import DeepSeek
from safebank_agent.runtime import Runtime
from safebank_agent.server import AgentServer
from safebank_agent.store import Store
from .test_savings_task_runtime import SavingsTaskRuntimeTest

def main():
 if os.environ.get('SAFEBANK_REAL_SAVINGS_QA')!='1':raise RuntimeError('Explicit paid opt-in required')
 replay_path=os.environ.get('SAFEBANK_REAL_REPLAY_CAPTURE')
 if not replay_path:
  sys.path.insert(0,os.environ['SAFEBANK_MONEY_GUARD_MODULE'])
  from trial_money_guard import install
  install()
 data=json.loads(sys.stdin.buffer.read().decode('utf-8'))
 key='' if replay_path else re.search(r'sk-[A-Za-z0-9]{16,}',Path(os.environ['SAFEBANK_QA_KEY_FILE']).read_text(encoding='utf-8-sig')).group()
 capture={'responses':[],'validationErrors':[],'receipts':[]}
 capture_path=Path(os.environ['SAFEBANK_REAL_CAPTURE'])
 def save_capture():capture_path.write_text(json.dumps(capture,ensure_ascii=False,indent=2),encoding='utf-8')
 class CapturedModel(DeepSeek):
  def complete(self,payload):
   r=super().complete(payload);capture['responses'].append({'context':json.loads(payload['messages'][-1]['content']),'response':r});save_capture();return r
 model=CapturedModel(key,os.getenv('SAFEBANK_QA_MODEL_NAME','deepseek-flash'),max_sends=3)
 if not replay_path:
  def observe_provider(response):
   if response.status_code!=200:
    response.read()
    capture['providerError']={'status':response.status_code,'body':response.text[:3000].replace(key,'[REDACTED]')}
    save_capture()
  model.client.event_hooks['response']=[observe_provider]
 if replay_path:
  captured=json.loads(Path(replay_path).read_text(encoding='utf-8'))['responses']
  class ReplayModel(CapturedModel):
   mode='mock'
   def complete(self,payload):
    self.check_can_send();context=json.loads(payload['messages'][-1]['content']);saved=captured[self.sends]
    assert context['question']==saved['context']['question'],'Captured question mismatch'
    if 'facts' in context:assert context['facts']==saved['context']['facts'],'Captured facts mismatch'
    self.sends+=1;r=saved['response'];capture['responses'].append({'context':context,'response':r});save_capture();return r
  model=ReplayModel('','captured-deepseek',max_sends=3)
 class ObservedBank(BankClient):
  def __init__(self):
   super().__init__(data['url'],'x'*64);self.calls=0;self.validations=0;self.rendered=None
   def observe(response):
    if response.request.url.path.endswith('/answers/validate') and response.status_code!=200:
     response.read();capture['validationErrors'].append(response.json());save_capture()
   self.http.event_hooks['response']=[observe]
  def tool(self,body,token):
   self.calls+=1;r=super().tool(body,token);capture['receipts'].append(r);save_capture();return r
  def validate(self,body,token):
   self.validations+=1;r=super().validate(body,token);self.rendered=r.get('text');return r
 SavingsTaskRuntimeTest.setUpClass();bank=ObservedBank();server=None
 try:
  config=SavingsTaskRuntimeTest.config
  runtime=Runtime(Store(config),config,bank,model)
  server=AgentServer(('127.0.0.1',0),runtime,'x'*64)
  thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  with httpx.Client(base_url='http://127.0.0.1:'+str(server.server_address[1]),headers={'Authorization':'Bearer '+'x'*64},timeout=10) as http:
   r=http.post('/internal/agent/turns',json=data['request']);r.raise_for_status();view=r.json()
   deadline=time.monotonic()+200
   while view['state']=='PROCESSING' and time.monotonic()<deadline:
    time.sleep(.2);r=http.get('/internal/agent/turns/'+data['request']['turnId']);r.raise_for_status();view=r.json()
  print(json.dumps({'state':view['state'],'errorCode':view.get('errorCode'),'modelFailure':model.last_failure,'result':view.get('result'),'modelSends':model.sends,'providerSends':0 if replay_path else model.sends,'toolCalls':bank.calls,'javaValidations':bank.validations,'rendered':bank.rendered,'pythonHttp':True,'bankModelMode':'captured-replay' if replay_path else 'deepseek'}),flush=True)
 finally:
  if server:server.shutdown();server.server_close()
  bank.http.close();model.client.close();SavingsTaskRuntimeTest.tearDownClass()

if __name__=='__main__':main()
