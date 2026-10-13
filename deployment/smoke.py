"""Wait for mock services and exercise a readonly chat; never use a paid mode."""
import http.cookiejar,json,time,urllib.error,urllib.request,uuid
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
base='http://127.0.0.1:8134'
def request(path,body=None,csrf=None):
    headers={'Content-Type':'application/json'}
    if csrf:headers['X-CSRF-Token']=csrf
    req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    with client.open(req,timeout=15) as response:return json.load(response)
for attempt in range(150):
    try:
        session=request('/api/session')
        if session['mode']!='LANGGRAPH_MOCK':raise SystemExit('Smoke refuses any paid mode')
        break
    except (urllib.error.URLError,TimeoutError,ConnectionError):
        if attempt==149:raise
        time.sleep(2)
context=request('/api/conversations',{},session['csrf'])
body={'text':'分析2026-09账单','requestId':uuid.uuid4().hex,'conversationId':context['conversationId'],'expectedContextVersion':context['version'],'modelRequestLimit':3}
for attempt in range(30):
    try:
        answer=request('/api/chat',body,session['csrf']);break
    except urllib.error.HTTPError as error:
        data=json.load(error)
        if data.get('code') not in ('CONTEXT_PROCESSING','AGENT_RECONCILING') or attempt==29:raise
        time.sleep(1)
assert answer.get('text') and not answer.get('actions'),answer
print('PASS: mock webpage session, conversation and readonly statement chat; provider calls 0')
