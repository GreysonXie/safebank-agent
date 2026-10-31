"""One HTTP send per reserved effect; offline mode never calls a provider."""
import json
import re
import threading
import unicodedata
from time import monotonic
from pathlib import Path
import httpx
from .contracts import ProtocolError,ModelRequestRejected,canonical
from .conversation import bounded_clarification_context,defers_operation,requires_amendment_target

class DeepSeek:
    mode='deepseek'
    def __init__(self,key,model,url='https://api.deepseek.com',client=None,max_sends=None):
        self.key=key;self.name=model;self.url=url.rstrip('/')
        self.max_sends=max_sends;self.sends=0;self.lock=threading.Lock()
        self.last_failure=None
        self.client=client or httpx.Client(timeout=httpx.Timeout(60,connect=5,write=10,pool=5),follow_redirects=False)
    def check_can_send(self):
        with self.lock:
            if self.max_sends is not None and self.sends>=self.max_sends:raise ProtocolError('MODEL_STAGE_CAP')
    def request_body(self,payload):
        return {'model':self.name,'messages':payload['messages'],'max_tokens':min(payload['max_tokens'],2048),'response_format':{'type':'json_object'},'stream':False,'thinking':{'type':'disabled'}}
    def complete(self,payload):
        with self.lock:
            if self.max_sends is not None and self.sends>=self.max_sends:raise ProtocolError('MODEL_STAGE_CAP')
            self.sends+=1
            self.last_failure=None
        body=self.request_body(payload)
        started=monotonic()
        try:
            with self.client.stream('POST',self.url+'/chat/completions',json=body,headers={'Authorization':'Bearer '+self.key}) as response:
                if response.status_code!=200:
                    self.last_failure='HTTP_'+str(response.status_code)
                    if response.status_code in ModelRequestRejected.STATUSES:
                        raise ModelRequestRejected(response.status_code)
                    raise ProtocolError('MODEL_HTTP_FAILED')
                chunks=[];size=0
                for chunk in response.iter_bytes():
                    # Checked on data arrival; one blocked read remains bounded by read timeout.
                    if monotonic()-started>90:
                        self.last_failure='TotalDeadline'
                        raise ProtocolError('MODEL_DEADLINE_EXCEEDED')
                    size+=len(chunk)
                    if size>65536:raise ProtocolError('MODEL_RESPONSE_TOO_LARGE')
                    chunks.append(chunk)
                reply=json.loads(b''.join(chunks))
            return decode_completion(reply)
        except ProtocolError:raise
        except Exception as error:
            # Class name only: exception text can include credentials or request data.
            self.last_failure=type(error).__name__
            raise ProtocolError('MODEL_TRANSPORT_OR_FORMAT_FAILED') from None

class OpenAICompatible(DeepSeek):
    """Chat Completions gateway; retain effect receipts and per-turn protection."""
    mode='openai'
    def request_body(self,payload):
        body=super().request_body(payload)
        body.pop('thinking')
        return body

def decode_completion(reply):
    choices=reply.get('choices')
    choice=choices[0] if isinstance(choices,list) and choices and isinstance(choices[0],dict) else {}
    message=choice.get('message')
    raw=message.get('content') if isinstance(message,dict) else None
    result={'content':None,'id':reply.get('id'),'model':reply.get('model'),'usage':reply.get('usage'),
            'raw_content':raw if isinstance(raw,str) else None,'finish_reason':choice.get('finish_reason')}
    if not choice or not isinstance(raw,str):result['response_error']='MODEL_RESPONSE_INVALID'
    elif choice.get('finish_reason')!='stop':result['response_error']='MODEL_INCOMPLETE'
    else:
        try:
            content=json.loads(raw);pending=[(content,0)]
            while pending:
                value,depth=pending.pop()
                if depth>32:raise ValueError('Content nesting exceeds protocol envelope')
                if isinstance(value,dict):pending.extend((item,depth+1) for item in value.values())
                elif isinstance(value,list):pending.extend((item,depth+1) for item in value)
            canonical(content).encode('utf-8');result['content']=content
        except (ValueError,RecursionError):result['response_error']='MODEL_CONTENT_NOT_JSON'
    return result

class ManagedModel:
    """The Java service holds credentials; Python retains only a revision."""
    mode='managed'
    # 只发送 Java 侧 Request 记录声明的字段：预算预留的返回可能带 remaining/used 等附加信息，
    # 原样回传会被 Java 的严格绑定拒绝（"Unrecognized field"），真实模型因此根本发不出去。
    REQUEST_KEYS=('reservationId','operationKey','inputHash','javaTurnId','purpose')
    def __init__(self,bank,token,revision):
        self.bank=bank;self.token=token;self.revision=revision
    def check_can_send(self):pass
    def complete(self,payload,reservation):
        body={key:reservation[key] for key in self.REQUEST_KEYS if key in reservation}
        body['revision']=self.revision;body['payload']=payload
        reply=self.bank.model_complete(body,self.token)
        return decode_completion(reply)

class MockModel:
    """Finite offline fixtures, explicitly labelled; not evidence of model quality."""
    mode='mock'
    def complete(self,payload):
        context=json.loads(payload['messages'][-1]['content']);question=context['question']
        if question=='只解释合同边界，不要重复金额':
            value={'kind':'general','answer':'银行侧撤销只影响代扣授权，不等于商户合同终止或退款；退订和退款需按商户合同及实际处理结果核对。'} if 'facts' in context else {'kind':'general','tools':[]}
            return {'content':value,'model':'offline-fixture','usage':None}
        if 'facts' not in context and defers_operation(question):
            return {'content':{'kind':'general','tools':[]},'model':'offline-fixture','usage':None}
        if 'facts' not in context and requires_amendment_target(question,context.get('previous_plan',{}),context.get('clarification_context',[]),context.get('previous_question','')):
            return {'content':{'kind':'clarification','tools':[],'question':'你希望修改哪项操作的金额？请说明具体操作。'},'model':'offline-fixture','usage':None}
        if context.get('answer_contract',{}).get('operation_deferred'):
            return {'content':{'kind':'general','answer':'好的，先不办理。本轮没有新建操作预览；已有确认项如需取消，请在业务页单独核对。'},'model':'offline-fixture','usage':None}
        if 'facts' not in context:
            # Exact opt-in fixture only: this is not a natural-language planner.
            demo=json.loads((Path(__file__).parent/'fixtures/savings_task_demo.json').read_text(encoding='utf-8'))
            if question==demo['question']:
                return {'content':{'kind':'savings_task','taskPlan':demo['taskPlan']},'model':'offline-fixture','usage':None}
            identifier=context.get('previous_workflow_id')
            if question in ('查看储蓄任务进度','看看任务进度','这个计划包含哪些操作，为什么只显示一个待确认步骤？') and isinstance(identifier,str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',identifier):
                return {'content':{'kind':'analysis','tools':[{'tool':'savings_task_status','args':{'workflowId':identifier}}]},'model':'offline-fixture','usage':None}
            question=unicodedata.normalize('NFKC',question)
            request_question=question
            if context.get('previous_plan',{}).get('kind')=='clarification' and not any(x in question for x in ('分析','账单','余额','取消','撤销','退款','不要','不用')):
                history=bounded_clarification_context(context.get('clarification_context',[]))
                question='，'.join([*(history or [context.get('previous_question','')]),question])
            previous=context.get('previous_plan',{}).get('tools',[])
            prior=next((t for t in previous if t.get('tool')=='transfer_preview'),None)
            transfer=bool(re.search(r'转(?:账|给|[0-9])|给.+转',question)) or bool(prior and any(x in question for x in ('改成','改为','换成','改给','张三','同学','同事','付款账户','日常账户')))
            months=re.findall(r'20\d{2}-(?:0[1-9]|1[0-2])',question)
            if not months:
                previous=context.get('previous_plan',{}).get('tools',[])
                months=[m for t in previous if t.get('tool') in ('statement','statement_compare') for m in t.get('args',{}).values() if isinstance(m,str) and re.fullmatch(r'20\d{2}-(?:0[1-9]|1[0-2])',m)]
            consultation=any(x in request_question for x in ('能否','能不能','可以','是否','影响','风险','建议','应该','要不要','吗','么','不要','不想','不用'))
            if transfer and consultation:
                value={'kind':'general','tools':[]}
            elif transfer:
                args=dict(prior['args']) if prior else {}
                clarifying=context.get('previous_plan',{}).get('kind')=='clarification'
                fragments=[*(bounded_clarification_context(context.get('clarification_context',[])) or [context.get('previous_question','')]),request_question] if clarifying else [request_question]
                match=None
                for fragment in reversed(fragments):
                    fragment=unicodedata.normalize('NFKC',fragment)
                    matches=list(re.finditer(r'(?:转(?:账)?|改成|改为|换成)\s*([0-9]+)(?:\.([0-9]{1,2}))?\s*(?:元|块)?',fragment))
                    match=matches[-1] if matches else re.fullmatch(r'\s*([0-9]+)(?:\.([0-9]{1,2}))?\s*(?:元|块)?[。.!！]?\s*',fragment) if clarifying else None
                    if match:break
                if match:args['amountMinor']=int(match[1])*100+int((match[2] or '').ljust(2,'0') or '0')
                for fragment in reversed(fragments):
                    targets=list(re.finditer(r'同学|同事|张三',fragment))
                    if targets:args['recipientQuery']=targets[-1][0];break
                if '日常账户' in question:args['sourceQuery']='日常账户'
                missing=[name for name in ('sourceQuery','recipientQuery','amountMinor') if name not in args]
                labels={'sourceQuery':'付款账户','recipientQuery':'收款人','amountMinor':'金额'}
                value={'kind':'clarification','tools':[],'question':'请说明'+'、'.join(labels[key] for key in missing)+'。'} if missing else {'kind':'analysis','tools':[{'tool':'transfer_preview','args':args}]}
            elif (re.search(r'(?:请|帮我|我要)?(?:撤销|取消|退订).*(?:音乐|视频)服务',question) or re.search(r'(?:音乐|视频)服务撤销预览',question)) and not consultation:
                merchant='视频服务' if '视频' in question else '音乐服务'
                value={'kind':'analysis','tools':[{'tool':'revoke_preview','args':{'merchantQuery':merchant}}]}
            elif '余额' in question or '账户' in question:value={'kind':'analysis','tools':[{'tool':'account_query','args':{}}]}
            elif any(word in question for word in ('代扣','授权','收费','季度','月付','取消','退订')) or ('周期' in question and '历史' not in question):value={'kind':'analysis','tools':[{'tool':'mandate_receipt','args':{}}]}
            elif '重复' in question or '周期' in question:value={'kind':'analysis','tools':[{'tool':'recurring_analysis','args':{}}]}
            elif any(word in question for word in ('转账','退款','撤销')):value={'kind':'general','tools':[]}
            elif months:
                name='statement_compare' if len(months)>=2 else 'statement'
                args={'month':months[0]}
                if len(months)>=2:args['comparisonMonth']=months[1]
                value={'kind':'analysis','tools':[{'tool':name,'args':args}]}
            else:value={'kind':'clarification','question':'请说明要分析的账单月份，或要查询的账户信息。','tools':[]}
        else:
            facts=context['facts'];expenses=[k for k in facts if k.endswith('.expense_minor')]
            focus='大额' in question or '核对' in question
            candidates=[k for k in facts if '.anomaly_candidates.' in k and k.endswith('.spending_minor')]
            mandate_rows={}
            for key,fact in facts.items():
                match=re.fullmatch(r'(mandates_latest\.\d+)\.([^.]*)',key)
                if match:mandate_rows.setdefault(match[1],{})[match[2]]=fact
            actionable_notices=[notice for notice in context.get('tool_notices',[]) if notice.get('code')!='TRANSACTION_CATALOG_PARTIAL']
            if actionable_notices and not mandate_rows:
                notice=actionable_notices[0];answer=notice['message']
                if notice.get('code')=='RECIPIENT_CLARIFICATION_REQUIRED':
                    refs=context.get('answer_contract',{}).get('transfer_clarification',{}).get('candidate_distinction_refs',[])
                    choices={}
                    for ref in refs:choices.setdefault(ref.rsplit('.',1)[0],ref)
                    if choices:answer='请确认你指的是'+ '，还是'.join(choices.values())+'对应的收款人？尚未生成转账预览。'
                    if context.get('answer_contract',{}).get('transfer_clarification',{}).get('has_indistinguishable_candidates'):answer+='部分候选现有信息无法区分，请先核对并补充备注。'
            elif any(k in facts for k in ('savings_task.phase','savings_task_status.phase')):
                prefix='savings_task_status' if 'savings_task_status.phase' in facts else 'savings_task'
                answer='本次核验信息：{{'+prefix+'.phase}}。'
                if prefix+'.state_label' in facts:answer+='任务状态为{{'+prefix+'.state_label}}。'
                elif prefix+'.state' in facts:answer+='任务状态为{{'+prefix+'.state}}。'
                if prefix+'.pending.merchant' in facts:
                    answer+='当前需要核对{{'+prefix+'.pending.merchant}}'
                    answer+='的{{'+prefix+'.pending.kind_label}}。' if prefix+'.pending.kind_label' in facts else '。'
                answer+='任务登记不代表银行操作执行成功；请在原任务卡查看回执，未执行的操作仍需逐项确认。'
                if any(word in question for word in ('范围','包含','只显示','计划里')):
                    answer='当前信息：{{'+prefix+'.phase}}。'
                    for field,label in (('plan_scope.revocations_count','登记计划中的撤销项目数'),('plan_scope.scheduled_transfers_count','计划中的定时转存项目数'),('progress.created_steps_count','按步骤标识合并后的当前步骤数')):
                        if prefix+'.'+field in facts:answer+=label+'为{{'+prefix+'.'+field+'}}。'
                    if prefix+'.plan_scope.steps_scope' in facts:answer+='{{'+prefix+'.plan_scope.steps_scope}}。'
                    if prefix+'.plan_scope.names_scope' in facts:answer+='{{'+prefix+'.plan_scope.names_scope}}。'
                    answer+='计划范围和步骤记录不能替代银行执行回执，也不保证未来到账。'
            elif 'preview_transfer.amount_minor' in facts:
                answer='待确认收款对象{{preview_transfer.recipient_label}}，金额{{preview_transfer.amount_minor}}。尚未执行转账，请核对操作卡并明确确认。'
            elif 'preview_revoke.merchant' in facts:
                answer='{{preview_revoke.merchant}}的撤销预览已生成。尚未执行撤销，请核对操作卡；银行侧撤销不证明商户合同终止或退款。'
            elif mandate_rows or 'mandates_summary.known_monthly_total_minor' in facts:
                answer=self.mandate_answer(question,facts,mandate_rows,{row['rowId']:row.get('billingDescription') for row in context.get('mandate_rows',[])})
            elif focus and candidates:
                ref=candidates[0];prefix=ref.rsplit('.',1)[0]
                related=[k for k in (prefix+'.booked_on',prefix+'.merchant',ref) if k in facts]
                answer='需要重点核对的支出记录是'+'、'.join('{{'+k+'}}' for k in related)+'。金额大小不能证明异常；请结合用途、报价和凭证核对。'
            elif expenses:
                answer='查询月份的支出为'+'、'.join('{{'+k+'}}' for k in expenses)+'。该口径不含本人账户内部转账，退款计入入账，不抵减这里的支出。'
                if focus:answer='当前账单支出为{{'+expenses[0]+'}}。大额核对还需结合具体交易的用途、报价和凭证；不能仅凭金额判定安全或异常。'
            else:
                balances=[k for k in facts if k.endswith('.balance_minor')]
                answer='查询时的账户余额分别为'+'、'.join('{{'+k+'}}' for k in balances)+'。请按账户核对。' if balances else '银行操作可先生成待确认预览；授权和执行须在操作卡上核对参数并逐项确认。'
            clarify=bool(context.get('tool_notices') and context['tool_notices'][0]['code'] in ('RECIPIENT_CLARIFICATION_REQUIRED','SOURCE_CLARIFICATION_REQUIRED','MANDATE_CLARIFICATION_REQUIRED'))
            value={'kind':'clarification' if clarify else 'analysis' if facts else 'general','answer':answer}
        return {'content':value,'model':'offline-fixture','usage':None}

    @staticmethod
    def mandate_answer(question,facts,rows,descriptions=None):
        ref=lambda key:'{{'+key+'}}'
        name=lambda text:re.sub(r'〔|〕|演示|[（(]合成[）)]','',text).strip()
        selected=[(prefix,fields) for prefix,fields in rows.items() if name(fields.get('merchant',{}).get('display','')) and name(fields['merchant']['display']) in name(question)]
        focused=bool(selected)
        selected=selected or list(rows.items())
        parts=['依据本次确认查询的快照，']
        if not focused and any(word in question for word in ('月付','每月','每个月','合计','节省','可省')) and 'mandates_summary.known_monthly_total_minor' in facts:
            parts.append('查询时有效且明确按月收费的授权合计为'+ref('mandates_summary.known_monthly_total_minor')+'；收费周期未知或按季度收费的授权不计入该合计。这不代表取消后保证节省。')
        else:
            cycles={'MONTHLY':'按月收费','QUARTERLY':'按季度收费','YEARLY':'按年收费','UNKNOWN':'收费周期未知，不能当作月付'}
            for prefix,fields in selected:
                if 'merchant' not in fields:continue
                cycle=(descriptions or {}).get(prefix) or cycles.get(fields.get('billing_interval',{}).get('display',''),'收费周期尚未确认')
                text=ref(prefix+'.merchant')+'：'+cycle
                if 'amount_minor' in fields:text+='，约定金额'+ref(prefix+'.amount_minor')
                if 'state_label' in fields:text+='，'+ref(prefix+'.state_label')
                parts.append(text+'。')
        if any(word in question for word in ('取消','撤销','退订','合同')):
            parts.append('银行侧撤销须到业务页逐项确认；无法确认商户合同是否终止或以后是否继续收费。')
        if any(word in question for word in ('使用','值得','常用')):
            parts.append('银行没有服务使用记录，需要结合你的实际使用情况判断。')
        if len(parts)==1:parts.append('未列出授权；如需重新查询，请到业务页确认范围。')
        return ''.join(parts)
