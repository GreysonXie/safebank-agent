"""One HTTP send per reserved effect; offline mode never calls a provider."""
import json
import re
import threading
import httpx
from .contracts import ProtocolError,canonical

class DeepSeek:
    mode='deepseek'
    def __init__(self,key,model,url='https://api.deepseek.com',client=None,max_sends=None):
        self.key=key;self.name=model;self.url=url
        self.max_sends=max_sends;self.sends=0;self.lock=threading.Lock()
        self.client=client or httpx.Client(timeout=httpx.Timeout(30,connect=5),follow_redirects=False)
    def check_can_send(self):
        with self.lock:
            if self.max_sends is not None and self.sends>=self.max_sends:raise ProtocolError('MODEL_STAGE_CAP')
    def complete(self,payload):
        with self.lock:
            if self.max_sends is not None and self.sends>=self.max_sends:raise ProtocolError('MODEL_STAGE_CAP')
            self.sends+=1
        body={'model':self.name,'messages':payload['messages'],'max_tokens':min(payload['max_tokens'],2048),'response_format':{'type':'json_object'},'stream':False,'thinking':{'type':'disabled'}}
        try:
            with self.client.stream('POST',self.url+'/chat/completions',json=body,headers={'Authorization':'Bearer '+self.key}) as response:
                if response.status_code!=200:raise ProtocolError('MODEL_HTTP_FAILED')
                chunks=[];size=0
                for chunk in response.iter_bytes():
                    size+=len(chunk)
                    if size>65536:raise ProtocolError('MODEL_RESPONSE_TOO_LARGE')
                    chunks.append(chunk)
                reply=json.loads(b''.join(chunks))
            choices=reply.get('choices')
            choice=choices[0] if isinstance(choices,list) and choices and isinstance(choices[0],dict) else {}
            message=choice.get('message')
            raw=message.get('content') if isinstance(message,dict) else None
            result={'content':None,'id':reply.get('id'),'model':reply.get('model'),'usage':reply.get('usage'),
                    'raw_content':raw if isinstance(raw,str) else None,'finish_reason':choice.get('finish_reason')}
            # Receiving a complete HTTP envelope is distinct from validating its
            # generated answer. Persist the envelope and usage before rejecting it.
            if not choice or not isinstance(raw,str):result['response_error']='MODEL_RESPONSE_INVALID'
            elif choice.get('finish_reason')!='stop':result['response_error']='MODEL_INCOMPLETE'
            else:
                try:
                    content=json.loads(raw)
                    pending=[(content,0)]
                    while pending:
                        value,depth=pending.pop()
                        if depth>32:raise ValueError('Content nesting exceeds protocol envelope')
                        if isinstance(value,dict):pending.extend((item,depth+1) for item in value.values())
                        elif isinstance(value,list):pending.extend((item,depth+1) for item in value)
                    # Test the same persistence encoding before adding parsed
                    # content: non-finite numbers/surrogates must not lose raw usage.
                    canonical(content).encode('utf-8')
                    result['content']=content
                except (ValueError,RecursionError):result['response_error']='MODEL_CONTENT_NOT_JSON'
            return result
        except ProtocolError:raise
        except Exception:raise ProtocolError('MODEL_TRANSPORT_OR_FORMAT_FAILED') from None

class MockModel:
    """Finite offline fixtures, explicitly labelled; not evidence of model quality."""
    mode='mock'
    def complete(self,payload):
        context=json.loads(payload['messages'][-1]['content']);question=context['question']
        if 'facts' not in context:
            request_question=question
            if context.get('previous_plan',{}).get('kind')=='clarification' and not any(x in question for x in ('分析','账单','余额','取消','撤销','退款','不要','不用')):
                question=context.get('previous_question','')+'，'+question
            previous=context.get('previous_plan',{}).get('tools',[])
            prior=next((t for t in previous if t.get('tool')=='transfer_preview'),None)
            transfer=bool(re.search(r'转(?:账|给|[0-9])|给.+转',question)) or bool(prior and any(x in question for x in ('改成','改为','改给','张三','同学','同事','付款账户','日常账户')))
            months=re.findall(r'20\d{2}-(?:0[1-9]|1[0-2])',question)
            if not months:
                previous=context.get('previous_plan',{}).get('tools',[])
                months=[m for t in previous if t.get('tool') in ('statement','statement_compare') for m in t.get('args',{}).values() if isinstance(m,str) and re.fullmatch(r'20\d{2}-(?:0[1-9]|1[0-2])',m)]
            consultation=any(x in request_question for x in ('能否','能不能','可以','是否','影响','风险','建议','应该','要不要','吗','么','不要','不想','不用'))
            if transfer and consultation:
                value={'kind':'general','tools':[]}
            elif transfer:
                args=dict(prior['args']) if prior else {}
                match=re.search(r'(?:转(?:账)?|改成)\s*([0-9]+)(?:\.([0-9]{1,2}))?\s*(?:元|块)?',question)
                if match:args['amountMinor']=int(match[1])*100+int((match[2] or '').ljust(2,'0') or '0')
                for recipient in ('同学','同事','张三'):
                    if recipient in question:args['recipientQuery']=recipient;break
                if '日常账户' in question:args['sourceQuery']='日常账户'
                missing=[name for name in ('sourceQuery','recipientQuery','amountMinor') if name not in args]
                labels={'sourceQuery':'付款账户','recipientQuery':'收款人','amountMinor':'金额'}
                value={'kind':'clarification','tools':[],'question':'请说明'+'、'.join(labels[key] for key in missing)+'。'} if missing else {'kind':'analysis','tools':[{'tool':'transfer_preview','args':args}]}
            elif re.search(r'(?:请|帮我|我要)?(?:撤销|取消|退订).*(?:音乐|视频)服务',question) and not consultation:
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
            if context.get('tool_notices') and not mandate_rows:
                notice=context['tool_notices'][0];answer=notice['message']
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
