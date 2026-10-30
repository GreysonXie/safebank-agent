"""Single-writer checkpoint runtime. Raw capabilities stay in volatile memory."""
import json
import threading
import uuid
import pymysql
from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
from langgraph.types import Command
from .contracts import ProtocolError, project, bank_hash
from .effects import Effects
from .graph import build, result
from .answers import GroundingRejection
from .safety import requests_rendered_credentials

class Runtime:
    def __init__(self, store, checkpoint_config, bank, model):
        self.store=store;self.bank=bank;self.adapter=model;self.tokens={};self.tokens_lock=threading.Lock()
        self.writer=threading.Lock();self.owner=uuid.uuid4().hex;self.current=None
        self.connection=pymysql.connect(**dict(checkpoint_config,cursorclass=pymysql.cursors.DictCursor),autocommit=True)
        self.saver=PyMySQLSaver(self.connection)
        # DDL/setup belongs to deployment migration, never restricted runtime users.
        self.graph=build(self.saver,self);self.effects=Effects(store)
    def restart(self,prior,run_id,binding,request):
        """陈旧/不可续的检查点：换新线程从头跑这一轮，避免历史残点永久阻塞该会话。"""
        thread=uuid.uuid4().hex
        self.store.rethread(run_id,binding['id'],thread)
        config={'configurable':{'thread_id':thread},'recursion_limit':16}
        return self.graph.invoke({'request':request,'clarification_context':[],'previous_question':prior.get('request',{}).get('text',''),'previous_plan':prior.get('plan',{}),'previous_workflow_id':prior.get('workflow_id'),'receipts':[],'answer':{},'repair':0,'validated':False},config)
    def close(self):
        if self.connection.open:self.connection.close()
    def accept(self,request):
        token=request.get('grant')
        if not isinstance(token,str) or not token:raise ProtocolError('MISSING_GRANT')
        run=self.store.accept(request)
        if run['state']=='PROCESSING':
            token=request.get('grant')
            if not isinstance(token,str) or not token:raise ProtocolError('MISSING_GRANT')
            with self.tokens_lock:self.tokens[run['id']]=token
        return run
    def token(self,state):
        with self.tokens_lock:token=self.tokens.get(state['request']['turnId'])
        if not token:raise ProtocolError('GRANT_REQUIRED')
        return token
    def lease(self,state):
        if not self.current or self.current.run_id!=state['request']['turnId']:raise ProtocolError('RUN_MISMATCH')
        return self.current
    def model(self,state,node,sequence,payload):
        lease=self.lease(state);reservation={}
        def reserve(key,h):
            self.adapter.check_can_send()
            r=self.bank.reserve({'operationKey':key,'inputHash':h,'javaTurnId':lease.run_id,'purpose':node},self.token(state));reservation.update(r,operationKey=key,inputHash=h,javaTurnId=lease.run_id,purpose=node)
            return r['reservationId']
        saved=self.effects.call(lease,node,sequence,'model',payload,lambda key:self.adapter.complete(payload),reserve if self.adapter.mode=='deepseek' else None)
        if self.adapter.mode=='deepseek':
            # Reload original reservation even when the effect was already SUCCEEDED.
            from .contracts import digest
            key=digest([lease.run_id,node,sequence,'model'])
            with self.store.transaction() as c:
                c.execute('SELECT reservation_id,input_hash FROM agent_effect_receipts WHERE operation_key=%s',(key,));r=c.fetchone()
            try:self.bank.record({'reservationId':r['reservation_id'],'operationKey':key,'inputHash':r['input_hash'],'javaTurnId':lease.run_id,'purpose':node,'success':True,'usage':saved.get('usage')},self.token(state))
            except ProtocolError:pass  # Reporting is idempotent; never resend provider for telemetry.
        if saved.get('response_error') in ('MODEL_INCOMPLETE','MODEL_RESPONSE_INVALID'):
            # Effect receipt and usage were saved above. Stop without resending
            # truncated/invalid envelopes; malformed answer JSON uses the existing
            # one-rewrite contract in the graph, with a new reserved effect.
            raise ProtocolError(saved['response_error'])
        return saved
    def tool(self,state,sequence,item):
        lease=self.lease(state)
        return self.effects.call(lease,'tools',sequence,'tool',item,lambda key:self.bank.tool({'operationKey':key,'inputHash':bank_hash(item['tool'],item['args']),**item},self.token(state)))
    def validate(self,state,value):
        self.store.check(self.lease(state))
        response=self.bank.validate(value,self.token(state))
        if response.get('resultHash')!=bank_hash('agent_result',value):raise ProtocolError('RESULT_HASH_MISMATCH')
        if isinstance(response.get('text'),str) and requests_rendered_credentials(response['text']):
            raise GroundingRejection(reason='UNSAFE_RENDERED_CREDENTIALS')
        self.store.check(self.lease(state));return response
    def note(self,state,code):
        if code not in ('ANSWER_FIELD_NORMALIZED','ANSWER_NUMERIC_SENTENCE_OMITTED','ANSWER_DISPLAY_DUPLICATE_REMOVED','ANSWER_DESCRIPTION_ALIAS_NORMALIZED'):raise ProtocolError('INVALID_EVENT')
        lease=self.lease(state)
        with self.store.transaction() as c:
            self.store.guard(c,lease)
            self.store.event(c,lease.run_id,'validate',code)
    def execute(self,run_id):
        # One connection and one checkpoint writer; this is not a distributed fence.
        with self.writer:
            try:lease=self.store.claim(run_id,self.owner)
            except ProtocolError:return
            self.current=lease;done=threading.Event()
            def beat():
                while not done.wait(10):
                    try:self.store.heartbeat(lease)
                    except ProtocolError:return
            heartbeat=threading.Thread(target=beat,daemon=True);heartbeat.start()
            try:
                run=self.store.get(run_id);request=json.loads(run['request_json']);binding=self.store.binding(run['binding_id'])
                config={'configurable':{'thread_id':run['thread_id']},'recursion_limit':16}
                checkpoint=self.graph.get_state(config)
                self.store.check(lease)
                if checkpoint.values.get('request',{}).get('turnId')==run_id:
                    output=self.graph.invoke(None,config) if checkpoint.next else checkpoint.values
                elif binding['waiting_run_id']:
                    waiting=self.store.get(binding['waiting_run_id'])
                    interrupts=[i for task in checkpoint.tasks for i in task.interrupts]
                    if waiting['state']!='NEEDS_CLARIFICATION' or waiting['output_version']!=run['input_version'] or len(interrupts)!=1 or interrupts[0].id!=waiting['interrupt_id'] or waiting['thread_id']!=run['thread_id']:
                        output=self.restart(checkpoint.values,run_id,binding,request)
                    else:
                        output=self.graph.invoke(Command(resume=request),config)
                elif checkpoint.next:
                    if checkpoint.values.get('request',{}).get('turnId')!=run_id:
                        output=self.restart(checkpoint.values,run_id,binding,request)
                    else:
                        output=self.graph.invoke(None,config)
                else:
                    prior=checkpoint.values
                    output=self.graph.invoke({'request':request,'clarification_context':[],'previous_question':prior.get('request',{}).get('text',''),'previous_plan':prior.get('plan',{}),'previous_workflow_id':prior.get('workflow_id'),'receipts':[],'answer':{},'repair':0,'validated':False},config)
                self.store.check(lease)
                interrupt_id=None
                if output.get('__interrupt__'):
                    interrupt_id=output['__interrupt__'][0].id
                    output['answer']={'kind':'clarification','answer':output['plan']['question']}
                    # A post-draft clarification may cite this turn's validated
                    # facts. Keep their receipts for the final Java validation;
                    # plan() already clears receipts at the start of every turn.
                value=result(output)
                # Also covers completed checkpoint -> result-store recovery and waits.
                self.validate(output,value)
                self.store.result(lease,value,value['answer']['kind'],interrupt_id)
                with self.tokens_lock:self.tokens.pop(run_id,None)
            except Exception as error:
                code=str(error) if isinstance(error,ProtocolError) else 'AGENT_RUNTIME_FAILED'
                try:
                    deferred=code=='BANK_CALLBACK_UNAVAILABLE' and self.store.defer_prepared(lease)
                    if deferred:
                        with self.tokens_lock:self.tokens.pop(run_id,None)
                    else:self.store.pause(lease,code)
                except ProtocolError:pass
            finally:
                done.set();heartbeat.join(1);self.current=None
