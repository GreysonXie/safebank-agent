import json,uuid
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

class WealthBank(Bank):
    def tool(self,request,token):
        self.calls+=1
        return {'evidenceId':uuid.uuid4().hex,'facts':{
            'wealth_products_latest.products.count':{'display':'3','number':'3','kind':'NUMBER','source':'产品目录'},
            'wealth_products_latest.products.1.name':{'display':'固收90天持有','kind':'TEXT','source':'产品目录'},
            'wealth_products_latest.products.2.name':{'display':'固收180天持有','kind':'TEXT','source':'产品目录'}}}

class WealthFocusRuntimeTest(RuntimeSchemaTest):
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
        self.store=Store(self.config);self.bank=WealthBank();self.model=CapturingModel()
        self.runtime=Runtime(self.store,self.config,self.bank,self.model)
        self.request=dict(userId='u1',conversationId=uuid.uuid4().hex,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=1,text='查看产品',grant='secret-token')
    def tearDown(self):self.runtime.close()
    def ask(self,text,version):
        self.request.update(text=text,expectedVersion=version,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,modelRequestLimit=2)
        run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
        self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
        self.store.ack(run['id'],version+1,saved['result_hash'])
        return saved
    def plans(self):return [payload for payload in self.model.payloads if 'facts' not in payload]
    def test_product_identity_survives_two_followups(self):
        self.model.responses=[{'kind':'analysis','tools':[{'tool':'wealth_products','args':{}}]},
            {'kind':'analysis','answer':'{{wealth_products_latest.products.1.name}}与{{wealth_products_latest.products.2.name}}的主要差异在最低持有期。'}]
        self.ask('请解释固收90天持有和固收180天持有的关键差异、主要限制和不确定项（产品编号：DEMO-WM-02、DEMO-WM-03）。',1)
        for version,question in ((2,'这两个哪个更灵活'),(3,'90天后会自动到账吗')):
            self.model.responses=[{'kind':'analysis','tools':[{'tool':'wealth_products','args':{}}]},
                {'kind':'analysis','answer':'最低持有期不等于自动到期；{{wealth_products_latest.products.1.name}}的到账时效未提供。'}]
            self.ask(question,version)
        plans=self.plans()
        self.assertGreaterEqual(len(plans),3)
        self.assertEqual(plans[0]['focus_products'],['DEMO-WM-02','DEMO-WM-03'])
        self.assertEqual(plans[1]['focus_products'],['DEMO-WM-02','DEMO-WM-03'])
        self.assertEqual(plans[2]['focus_products'],['DEMO-WM-02','DEMO-WM-03'])
        self.assertEqual([call['tool'] for call in [{'tool':tool} for tool in ['wealth_products']]*3],['wealth_products']*3,'三次都只调用一次目录工具')
    def test_switching_products_replaces_focus(self):
        self.model.responses=[{'kind':'analysis','tools':[{'tool':'wealth_products','args':{}}]},
            {'kind':'analysis','answer':'{{wealth_products_latest.products.count}}个产品。'}]
        self.ask('请解释固收90天持有和固收180天持有（产品编号：DEMO-WM-02、DEMO-WM-03）。',1)
        self.model.responses=[{'kind':'analysis','tools':[{'tool':'wealth_products','args':{}}]},
            {'kind':'analysis','answer':'{{wealth_products_latest.products.count}}个产品。'}]
        self.ask('换成 DEMO-WM-01 看看',2)
        plans=self.plans()
        self.assertEqual(plans[0]['focus_products'],['DEMO-WM-02','DEMO-WM-03'])
        self.assertEqual(plans[-1]['focus_products'],['DEMO-WM-01'])
