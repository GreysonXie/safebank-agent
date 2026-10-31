import json
import time
import httpx
from .contracts import ModelRequestRejected, ProtocolError
from .answers import GroundingRejection
import re

# T020.2-R2：业务性拒绝（参数不可用、样本不足、对象不存在、余额/限额不足）不是效果失败，
# 把服务端结果原样交给回答阶段澄清，而不是折叠成断链。模型/预算类错误仍按错误处理。
BUSINESS_REJECTIONS={'VALIDATION_ERROR','SAMPLE_INSUFFICIENT','NOT_FOUND','HISTORY_UNAVAILABLE','INSUFFICIENT_FUNDS','LIMIT_EXCEEDED'}

class BankClient:
    def __init__(self,url,key):
        self.url=url;self.key=key
        self.http=httpx.Client(timeout=3,follow_redirects=False)
    def call(self,path,body,token,deadline=3):
        started=time.monotonic()
        try:
            with self.http.stream('POST',self.url+path,json=body,headers={'Authorization':'Bearer '+self.key,'X-Agent-Grant':token},timeout=deadline) as response:
                if response.status_code>=500:raise ProtocolError('BANK_CALLBACK_UNAVAILABLE')
                chunks=[];size=0
                for chunk in response.iter_bytes():
                    size+=len(chunk)
                    if size>262144 or time.monotonic()-started>deadline:raise ProtocolError('BANK_CALLBACK_LIMIT')
                    chunks.append(chunk)
                data=json.loads(b''.join(chunks))
                if response.status_code!=200:
                    if path=='/internal/agent/model/complete':
                        code=data.get('code') if isinstance(data,dict) else None
                        if isinstance(code,str) and code.startswith('MODEL_REQUEST_REJECTED_'):
                            # 提供方明确拒绝（鉴权失败、模型不存在、参数非法）：这是确定结果，
                            # 不能当成"发送结果未知"，否则客户只会看到"结果尚未确定"而查不到真实原因。
                            status=code.rsplit('_',1)[-1]
                            if status.isdigit() and int(status) in ModelRequestRejected.STATUSES:
                                raise ModelRequestRejected(int(status))
                        if code in ('MODEL_CONFIGURATION_CHANGED','MODEL_REQUEST_UNCERTAIN','MODEL_UNAVAILABLE','MODEL_API_ERROR','MODEL_RESPONSE_INVALID'):
                            raise ProtocolError(code)
                        if isinstance(code,str) and re.fullmatch(r'MODEL_[A-Z0-9_]{1,60}',code):
                            # 其它模型类错误码按真实原因上报，不折叠成通用的回调拒绝。
                            raise ProtocolError(code)
                    if path=='/internal/agent/model-budget/reserve' and isinstance(data,dict) and data.get('code') in ('MODEL_REQUEST_BUDGET_EXCEEDED','MODEL_TURN_BUDGET_EXCEEDED','MODEL_DEBUG_BUDGET_INVALID'):
                        raise ProtocolError(data['code'])
                    if path.endswith('/validate') and isinstance(data,dict) and data.get('code')=='ANSWER_NOT_GROUNDED':
                        message=data.get('message','')
                        match=re.match(r'^(?:回答中手写了数字或金额|回答数字)〔([^〔〕\n]{1,80})〕',message) if isinstance(message,str) else None
                        reason='BANK_REJECTED'
                        if isinstance(message,str):
                            if message.startswith('没有银行查询事实'):reason='NO_QUERY_FACTS'
                            elif message.startswith('业务分析至少引用'):reason='NO_FACT_REFERENCE'
                            elif message.startswith('事实引用不存在'):reason='INVALID_REFERENCE'
                            elif re.match(r'^回答中的〔[^〔〕\n]{1,80}〕像是在宣称执行成功',message):reason='EXECUTION_CLAIM'
                            elif match:reason='NUMERIC_LITERAL'
                        raise GroundingRejection(match.group(1) if match else None,reason)
                    code=data.get('code') if isinstance(data,dict) else None
                    if code in BUSINESS_REJECTIONS:return data
                    if path=='/internal/agent/model/complete':
                        # 模型调用失败必须带可定位原因（状态码 + 服务端错误码/响应摘要），不能只说"回调被拒"。
                        detail=code if isinstance(code,str) and code else json.dumps(data,ensure_ascii=False,default=str)
                        hint='_'.join(part for part in (str(response.status_code),detail[:120]) if part)
                        raise ProtocolError('MODEL_CALLBACK_REJECTED_'+re.sub(r'[^A-Za-z0-9_]+','_',hint)[:80])
                    raise ProtocolError('BANK_CALLBACK_REJECTED')
                return data
        except ProtocolError:raise
        except Exception:raise ProtocolError('BANK_CALLBACK_UNAVAILABLE') from None
    def tool(self,body,token):return self.call('/internal/agent/tools',body,token)
    def validate(self,body,token):return self.call('/internal/agent/answers/validate',body,token)
    def reserve(self,body,token):return self.call('/internal/agent/model-budget/reserve',body,token)
    def record(self,body,token):return self.call('/internal/agent/model-budget/record',body,token)
    def model_configuration(self,token):return self.call('/internal/agent/model/configuration',{},token)
    def model_complete(self,body,token):return self.call('/internal/agent/model/complete',body,token,90)
