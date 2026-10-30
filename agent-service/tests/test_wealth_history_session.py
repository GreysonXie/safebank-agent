import json,re,uuid
from .test_store_effects import RuntimeSchemaTest
from .test_graph_runtime import Model,Bank

class CapturingModel(Model):
    mode='mock'
    def __init__(self):
        super().__init__()
        self.payloads=[]
    def complete(self,payload):
        self.payloads.append(json.loads(payload['messages'][-1]['content']))
        return super().complete(payload)

class HistoryBank(Bank):
    """受控桩：与 Java 契约一致的历史事实（含产品/实际区间/版本前缀与净值、变化、覆盖说明）。"""
    def __init__(self):
        super().__init__()
        self.args=[];self.current={}
    def tool(self,request,token):
        self.calls+=1
        args=request.get('args',{})
        self.args.append(args)
        product=str(args.get('productId',''));start=str(args.get('from',''));end=str(args.get('to',''))
        data={'DEMO-WM-02':('2026-09-01','2026-09-30','1.0000','1.0042','0.42'),
              'DEMO-WM-03':('2026-09-01','2026-09-30','1.0000','0.9931','-0.69')}.get(product)
        if data is None:return {'evidenceId':uuid.uuid4().hex,'facts':{}}
        actual_from,actual_to,nav_from,nav_to,change=data
        prefix='wealth_history_'+product+'_'+actual_from.replace('-','_')+'_'+actual_to.replace('-','_')+'_v1'
        facts={prefix+'.change_percent':{'display':change+'%','number':change,'kind':'PERCENT','source':'历史样本'},
               prefix+'.nav_from':{'display':nav_from+' 元/份','kind':'TEXT','source':'历史样本'},
               prefix+'.nav_to':{'display':nav_to+' 元/份','kind':'TEXT','source':'历史样本'},
               prefix+'.records':{'display':'22','number':'22','kind':'NUMBER','source':'历史样本'},
               prefix+'.coverage_note':{'display':'工作日样本（周一到周五），未核实节假日','kind':'TEXT','source':'历史样本'}}
        self.current.update(facts)
        return {'evidenceId':uuid.uuid4().hex,'facts':facts}
    def validate(self,result,token):
        from safebank_agent.contracts import ProtocolError,bank_hash
        text=result['answer']['answer']
        for reference in re.findall(r'\{\{([^{}]+)\}\}',text):
            if reference.strip() not in self.current:raise ProtocolError('BANK_VALIDATION_REJECTED')
        return {'text':text,'resultHash':bank_hash('agent_result',result)}

class WealthHistorySessionTest(RuntimeSchemaTest):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        import pymysql,hashlib
        from pathlib import Path
        from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
        with pymysql.connect(**dict(cls.config,cursorclass=pymysql.cursors.DictCursor),autocommit=True) as c:PyMySQLSaver(c).setup()
        checksum=hashlib.sha256((Path(__file__).resolve().parents[1]/'migrations/001_runtime.sql').read_text().encode()).hexdigest()
        with pymysql.connect(**cls.config,autocommit=True) as c,c.cursor() as s:
            s.execute('INSERT INTO agent_schema_migrations VALUES(1,%s,UTC_TIMESTAMP(6))',(checksum,))
    def setUp(self):
        from safebank_agent.store import Store
        from safebank_agent.runtime import Runtime
        self.store=Store(self.config);self.bank=HistoryBank();self.model=CapturingModel()
        self.runtime=Runtime(self.store,self.config,self.bank,self.model)
        self.request=dict(userId='u1',conversationId=uuid.uuid4().hex,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=1,text='查看产品',grant='secret-token')
    def tearDown(self):self.runtime.close()
    def ask(self,text,version,plan,answer):
        self.bank.current={}
        self.model.responses=[plan,{'kind':'analysis','answer':answer}]
        self.request.update(text=text,expectedVersion=version,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,modelRequestLimit=3)
        run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
        self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
        self.store.ack(run['id'],version+1,saved['result_hash'])
        return json.loads(saved['result_json'])
    def plans(self):return [payload for payload in self.model.payloads if 'facts' not in payload]
    def test_two_products_in_one_turn_are_both_citeable(self):
        prefix02='wealth_history_DEMO-WM-02_2026_09_01_2026_09_30_v1'
        self.ask('看固收90天持有产品9月的历史表现',1,
            {'kind':'analysis','tools':[{'tool':'wealth_history','args':{'productId':'DEMO-WM-02','month':'2026-09'}}]},
            '区间 {{%s.coverage_note}}；变化 {{%s.change_percent}}。'%(prefix02,prefix02))
        prefix03='wealth_history_DEMO-WM-03_2026_09_01_2026_09_30_v1'
        self.ask('同一时期180天那款呢',2,
            {'kind':'analysis','tools':[{'tool':'wealth_history','args':{'productId':'DEMO-WM-03','month':'2026-09'}}]},
            '同一区间变化 {{%s.change_percent}}。'%(prefix03,))
        self.ask('两款这段时间变化有什么不同',3,
            {'kind':'analysis','tools':[
                {'tool':'wealth_history','args':{'productId':'DEMO-WM-02','month':'2026-09'}},
                {'tool':'wealth_history','args':{'productId':'DEMO-WM-03','month':'2026-09'}}]},
            '90天持有 {{%s.change_percent}}，180天持有 {{%s.change_percent}}。'%(prefix02,prefix03))
        self.assertEqual(self.bank.args[0],{'productId':'DEMO-WM-02','month':'2026-09'})
        self.assertEqual(self.bank.args[1],{'productId':'DEMO-WM-03','month':'2026-09'},'只改产品，月份不变')
        plans=self.plans()
        drafts=[payload for payload in self.model.payloads if 'facts' in payload]
        self.assertIn(prefix02+'.change_percent',drafts[2]['facts'],'第三轮同时拿到第一款事实')
        self.assertIn(prefix03+'.change_percent',drafts[2]['facts'],'第二款事实不被遮蔽（历史工具不套最新筛选规则）')
    def test_missing_object_asks_and_does_not_guess(self):
        self.model.responses=[{'kind':'clarification','tools':[],'question':'请告诉我要看哪款产品的哪个月。'}]
        self.request.update(text='那款的历史表现呢',expectedVersion=1,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,modelRequestLimit=2)
        run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
        self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
        self.assertEqual(saved['result_kind'],'clarification')
        self.assertEqual(self.bank.calls,0,'没有对象时不静默查询')
        self.assertEqual(self.plans()[0].get('previous_plan'),{})

    def test_catalog_tool_cannot_answer_history_question(self):
        prefix='wealth_history_DEMO-WM-02_2026_09_01_2026_09_30_v1'
        self.model.responses=[
            {'kind':'analysis','tools':[{'tool':'wealth_products','args':{}}]},
            {'kind':'analysis','tools':[{'tool':'wealth_history','args':{'productId':'DEMO-WM-02','month':'2026-09'}}]},
            {'kind':'analysis','answer':'区间变化 {{'+prefix+'.change_percent}}。'}]
        self.request.update(text='固收90天持有的历史表现怎么样',expectedVersion=1,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,modelRequestLimit=3)
        run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
        self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
        self.assertEqual(self.bank.args,[{'productId':'DEMO-WM-02','month':'2026-09'}],'目录工具被拒后重规划为历史工具')
        plans=self.plans()
        self.assertTrue(any('HISTORY_QUERY_REQUIRED' in str(payload.get('validation_error','')) for payload in plans),'重规划原因被记录')
    def test_relative_month_words_are_accepted(self):
        prefix='wealth_history_DEMO-WM-03_2026_09_01_2026_09_30_v1'
        self.ask('看180天那款上个月的历史表现',1,
            {'kind':'analysis','tools':[{'tool':'wealth_history','args':{'productId':'DEMO-WM-03','month':'上月'}}]},
            '上个月变化 {{'+prefix+'.change_percent}}。')
        self.assertEqual(self.bank.args[0],{'productId':'DEMO-WM-03','month':'上月'},'自然语言月份原样交给服务端解析')