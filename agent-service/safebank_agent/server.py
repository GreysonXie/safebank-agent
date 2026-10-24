"""Loopback authenticated bounded API. No access logging or credentials in state."""
import hmac
import json
import re
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from .contracts import ProtocolError,canonical

class AgentServer(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,address,runtime,key):
        self.runtime=runtime;self.key=key;self.workers=ThreadPoolExecutor(max_workers=2,thread_name_prefix='agent')
        self.slots=threading.BoundedSemaphore(16);self.active=set();self.lock=threading.Lock();self.stopping=threading.Event()
        super().__init__(address,Handler)
    def submit(self,id):
        with self.lock:
            if id in self.active:return
            if not self.slots.acquire(blocking=False):raise ProtocolError('AGENT_BUSY')
            self.active.add(id)
        def execute():
            try:self.runtime.execute(id)
            finally:
                with self.lock:self.active.discard(id)
                self.slots.release()
        self.workers.submit(execute)
    def server_close(self):
        self.stopping.set();self.workers.shutdown(wait=True);self.runtime.close();super().server_close()

class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup();self.connection.settimeout(5)
    def log_message(self,*args):pass
    def respond(self,status,body):
        encoded=canonical(body).encode('utf-8')
        if len(encoded)>65536:status=413;encoded=b'{"code":"RESPONSE_TOO_LARGE"}'
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(encoded)));self.end_headers()
        try:self.wfile.write(encoded)
        except (BrokenPipeError,ConnectionResetError):pass
    def view(self,id):
        run=self.server.runtime.store.get(id)
        if not run:return {'state':'NO_RUN'}
        state=run['state']
        if state=='PROCESSING':
            with self.server.runtime.tokens_lock:has_grant=id in self.server.runtime.tokens
            with self.server.lock:active=id in self.server.active
            if not has_grant and not active:state='RECOVERY_REQUIRED'
        body={'state':state,'turnId':id}
        if run['result_json']:body.update(result=json.loads(run['result_json']),resultHash=run['result_hash'])
        if run['output_version']:body['outputVersion']=run['output_version']
        if run['error_code']:body['errorCode']=run['error_code']
        return body
    def handle_request(self):
        if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+self.server.key):return self.respond(403,{'code':'INTERNAL_AUTH_REQUIRED'})
        match=re.fullmatch(r'/internal/agent/turns(?:/([A-Za-z0-9_-]{1,80})(/(?:ack|recover))?)?',self.path)
        if self.path=='/internal/agent/health' and self.command=='GET':return self.respond(200,{'status':'UP','framework':'LangGraph','modelMode':self.server.runtime.adapter.mode})
        if not match:return self.respond(404,{'code':'NOT_FOUND'})
        id,suffix=match.groups()
        if self.command=='GET' and id and not suffix:return self.respond(200,self.view(id))
        if self.command!='POST':return self.respond(405,{'code':'METHOD_NOT_ALLOWED'})
        try:
            length=int(self.headers.get('Content-Length','-1'))
            if self.headers.get('Transfer-Encoding') or not 0<length<=65536:return self.respond(413,{'code':'BODY_LIMIT'})
            raw=self.rfile.read(length)
            if len(raw)!=length:raise ProtocolError('INCOMPLETE_BODY')
            body=json.loads(raw)
            if not isinstance(body,dict):raise ProtocolError('INVALID_BODY')
            if suffix=='/ack':
                if set(body)!={'turnId','outputVersion','resultHash'} or body['turnId']!=id:raise ProtocolError('ACK_CONFLICT')
                self.server.runtime.store.ack(id,body['outputVersion'],body['resultHash'])
            elif suffix=='/recover':
                if set(body)!={'turnId','grant'} or body['turnId']!=id:raise ProtocolError('INVALID_RECOVERY')
                run=self.server.runtime.store.get(id)
                if not run or run['state']!='PROCESSING':raise ProtocolError('NOT_RECOVERABLE')
                request=json.loads(run['request_json']);request['grant']=body['grant']
                self.server.runtime.accept(request);self.server.submit(id)
            elif not id:
                if set(body)-{'modelRequestLimit'}!={'userId','conversationId','turnId','requestId','expectedVersion','text','grant'}:raise ProtocolError('INVALID_BODY')
                run=self.server.runtime.accept(body);id=run['id']
                if run['state']=='PROCESSING':self.server.submit(id)
                # Very brief completion wait keeps offline interactions convenient;
                # slow models return PROCESSING without resending the request.
                import time
                deadline=time.monotonic()+2
                while time.monotonic()<deadline and self.server.runtime.store.get(id)['state']=='PROCESSING':time.sleep(0.02)
            else:raise ProtocolError('INVALID_PATH')
            self.respond(200,self.view(id))
        except ProtocolError as error:self.respond(409,{'code':str(error)})
        except Exception:self.respond(400,{'code':'INVALID_REQUEST'})
    do_GET=handle_request
    do_POST=handle_request

def main():
    import os
    from .config import Config
    from .store import Store
    from .runtime import Runtime
    from .bank_client import BankClient
    from .models import DeepSeek,OpenAICompatible,MockModel
    config=Config.environment()
    store=Store(config.database)
    from .schema import verify
    verify(store)
    cap=int(os.environ['SAFEBANK_MODEL_SMOKE_CAP']) if os.getenv('SAFEBANK_MODEL_SMOKE_CAP') else None
    model_type={'deepseek':DeepSeek,'openai':OpenAICompatible}.get(config.model_mode)
    model=model_type(config.model_key,config.model_name,config.model_url,max_sends=cap) if model_type else MockModel()
    runtime=Runtime(store,config.database,BankClient(config.bank_url,config.service_key),model)
    server=AgentServer(('127.0.0.1',config.port),runtime,config.service_key)
    # Scan bounded expired runs; grant loss is reported to Java for renewal.
    def scan():
        while not server.stopping.wait(30):
            for id in runtime.store.expired():
                with runtime.tokens_lock:has_grant=id in runtime.tokens
                if has_grant:
                    try:server.submit(id)
                    except ProtocolError:break
    threading.Thread(target=scan,daemon=True).start()
    try:server.serve_forever(poll_interval=0.5)
    finally:server.server_close()

if __name__=='__main__':main()
