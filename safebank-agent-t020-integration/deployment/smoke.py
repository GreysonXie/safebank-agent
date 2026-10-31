"""Mock-only readonly statement and original-request replay smoke test."""
import argparse,http.cookiejar,json,time,urllib.error,urllib.parse,urllib.request,uuid
from pathlib import Path

class SmokeFailure(Exception):pass

def validate_base_url(value):
 try:
  p=urllib.parse.urlsplit(value)
  if p.scheme!='http' or p.hostname!='127.0.0.1' or p.username is not None or p.password is not None or p.path not in ('','/') or p.query or p.fragment or p.port is None or not 1<=p.port<=65535:raise ValueError()
 except ValueError:raise SmokeFailure('INVALID_LOOPBACK_URL') from None
 return 'http://127.0.0.1:'+str(p.port)

class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None

def make_request(base):
 client=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def request(path,body=None,csrf=None):
  headers={'Content-Type':'application/json'}
  if csrf:headers['X-CSRF-Token']=csrf
  value=urllib.request.Request(base+path,data=json.dumps(body).encode('utf-8') if body is not None else None,headers=headers)
  with client.open(value,timeout=10) as response:return json.load(response)
 return request

def run_smoke(request,wait_seconds=60):
 deadline=time.monotonic()+wait_seconds
 while True:
  try:session=request('/api/session');break
  except (urllib.error.URLError,TimeoutError,ConnectionError):
   if time.monotonic()>=deadline:raise SmokeFailure('SESSION_UNAVAILABLE') from None
   time.sleep(1)
 if session.get('mode')!='LANGGRAPH_MOCK':raise SmokeFailure('PAID_MODE_REFUSED')
 csrf=session.get('csrf')
 if not isinstance(csrf,str) or not csrf:raise SmokeFailure('INVALID_SESSION')
 context=request('/api/conversations',{},csrf)
 if not isinstance(context.get('conversationId'),str) or type(context.get('version')) is not int:raise SmokeFailure('INVALID_CONTEXT')
 body={'text':'分析2026-09账单','requestId':uuid.uuid4().hex,'conversationId':context['conversationId'],'expectedContextVersion':context['version'],'modelRequestLimit':2}
 def chat():
  deadline=time.monotonic()+wait_seconds
  while True:
   try:return request('/api/chat',body,csrf)
   except urllib.error.HTTPError as error:
    try:data=json.load(error)
    except ValueError:raise SmokeFailure('INVALID_CHAT_ERROR') from None
    if data.get('code') not in ('CONTEXT_PROCESSING','AGENT_RECONCILING') or time.monotonic()>=deadline:raise SmokeFailure('CHAT_NOT_READY') from None
    time.sleep(1)
 answer=chat()
 if not isinstance(answer.get('text'),str) or not answer['text'].strip() or answer.get('actions') or answer.get('mode')!='LANGGRAPH_MOCK' or answer.get('conversationId')!=context['conversationId'] or answer.get('contextVersion')!=context['version']+1:raise SmokeFailure('UNEXPECTED_CHAT_RESULT')
 if chat()!=answer:raise SmokeFailure('ORIGINAL_REPLAY_MISMATCH')
 return {'ok':True,'mode':'LANGGRAPH_MOCK','checks':['session','readonly_statement','original_request_replay'],'providerRequests':0,'bankOperationsSubmitted':0}

def main(argv=None):
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--base-url',default='http://127.0.0.1:8134')
 parser.add_argument('--wait-seconds',type=int,default=60)
 parser.add_argument('--report',help='Optional sanitized JSON report.')
 args=parser.parse_args(argv)
 try:
  base=validate_base_url(args.base_url)
  if not 0<=args.wait_seconds<=600:raise SmokeFailure('INVALID_WAIT_LIMIT')
  report=run_smoke(make_request(base),args.wait_seconds)
 except SmokeFailure as error:report={'ok':False,'code':str(error)}
 except Exception:report={'ok':False,'code':'SMOKE_UNAVAILABLE_OR_INVALID_REPLY'}
 if args.report:
  path=Path(args.report);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print('PASS: free statement + original request replay; provider requests 0' if report['ok'] else 'FAIL: '+report['code']+'; no private reply details printed')
 return 0 if report['ok'] else 1

if __name__=='__main__':raise SystemExit(main())
