import json
import time
import httpx
from .contracts import ProtocolError
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
                        if code in ('MODEL_CONFIGURATION_CHANGED','MODEL_REQUEST_UNCERTAIN','MODEL_UNAVAILABLE','MODEL_API_ERROR','MODEL_RESPONSE_INVALID'):
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
