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

def fmt(minor):
    return '%s元'%(('%.2f'%(abs(minor)/100)).replace(',',''))

class StrictFilterBank(Bank):
    """受控工具桩：返回与 Java 契约一致的筛选事实（含逐笔日期/商户/金额、方向一致的合计），
       并用严格校验拒绝引用本轮目录之外的事实，不靠放行桩。"""
    ROWS={
        ('2026-09','支出','餐饮'):[(10000,'2026-09-02','早餐铺'),(10001,'2026-09-03','面馆'),(25000,'2026-09-04','家常菜')],
        ('2026-09','支出','购物'):[(30000,'2026-09-05','百货')],
        ('2026-10','支出','餐饮'):[(20000,'2026-10-05','快餐'),(15000,'2026-10-06','食堂')],
        ('2026-09','收入','全部'):[(120000,'2026-09-06','工资')],
    }
    def __init__(self):
        super().__init__()
        self.args=[];self.current={}
    def tool(self,request,token):
        self.calls+=1
        args=request.get('args',{})
        self.args.append(args)
        month=str(args.get('month','2026-09'));direction=str(args.get('direction','支出'));category=str(args.get('category','全部'))
        minimum=int(args.get('minAmountMinor',0));expense=direction=='支出'
        rows=[row for row in self.ROWS.get((month,direction,category),[]) if abs(row[0])>minimum]
        prefix='statement_filter_'+month.replace('-','_')+('_expense_' if expense else '_income_')+'min'+str(minimum)+('' if category=='全部' else '_'+category)
        total=sum(abs(row[0]) for row in rows)
        facts={prefix+'.matched_count':{'display':str(len(rows)),'number':str(len(rows)),'kind':'NUMBER','source':'筛选回执'},
               prefix+'.expense_minor':{'display':fmt(total if expense else 0),'number':str(total if expense else 0),'kind':'MONEY','source':'筛选回执'},
               prefix+'.income_minor':{'display':fmt(0 if expense else total),'number':str(0 if expense else total),'kind':'MONEY','source':'筛选回执'},
               prefix+'.list_scope_note':{'display':'合计按全部匹配记录计算；明细可能因容量或隔离被省略，省略不代表没有记录','kind':'TEXT','source':'筛选回执'},
               prefix+'.transactions.count':{'display':str(len(rows)),'number':str(len(rows)),'kind':'NUMBER','source':'筛选回执'}}
        for index,(amount,date,merchant) in enumerate(rows):
            facts[prefix+'.transactions.%d.date'%index]={'display':date,'kind':'TEXT','source':'筛选回执'}
            facts[prefix+'.transactions.%d.merchant'%index]={'display':merchant,'kind':'TEXT','source':'筛选回执'}
            facts[prefix+'.transactions.%d.amount_minor'%index]={'display':fmt(amount),'number':str(abs(amount)),'kind':'MONEY','source':'筛选回执'}
        self.current.update(facts)
        return {'evidenceId':uuid.uuid4().hex,'facts':facts}
    def validate(self,result,token):
        from safebank_agent.contracts import ProtocolError,bank_hash
        text=result['answer']['answer']
        for reference in re.findall(r'\{\{([^{}]+)\}\}',text):
            if reference.strip() not in self.current:raise ProtocolError('BANK_VALIDATION_REJECTED')
        return {'text':text,'resultHash':bank_hash('agent_result',result)}

class StatementFilterSessionTest(RuntimeSchemaTest):
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
        self.store=Store(self.config);self.bank=StrictFilterBank();self.model=CapturingModel()
        self.runtime=Runtime(self.store,self.config,self.bank,self.model)
        self.request=dict(userId='u1',conversationId=uuid.uuid4().hex,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=1,text='查询账单',grant='secret-token')
    def tearDown(self):self.runtime.close()
    def ask(self,text,version,plan,answer,expect_ready=True):
        self.bank.current={}
        self.model.responses=[plan,{'kind':'analysis','answer':answer}]
        self.request.update(text=text,expectedVersion=version,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,modelRequestLimit=2)
        run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
        if expect_ready:
            self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
            self.store.ack(run['id'],version+1,saved['result_hash'])
        return saved
    def plans(self):return [payload for payload in self.model.payloads if 'facts' not in payload]
    def test_followups_carry_conditions_and_change_only_named_field(self):
        food='statement_filter_2026_09_expense_min0_餐饮'
        self.ask('查2026年9月餐饮支出',1,
            {'kind':'analysis','tools':[{'tool':'statement_filter','args':{'month':'2026-09','category':'餐饮','direction':'支出','minAmountMinor':0}}]},
            '2026-09 餐饮支出合计{{%s.expense_minor}}，共{{%s.matched_count}}笔，例如{{%s.transactions.0.merchant}}。'%(food,food,food))
        over='statement_filter_2026_09_expense_min10000_餐饮'
        self.ask('只看超过100元的',2,
            {'kind':'analysis','tools':[{'tool':'statement_filter','args':{'month':'2026-09','category':'餐饮','direction':'支出','minAmountMinor':10000}}]},
            '超过100元后共{{%s.matched_count}}笔，合计{{%s.expense_minor}}，最大一笔{{%s.transactions.1.merchant}} {{%s.transactions.1.amount_minor}}。'%(over,over,over,over))
        self.ask('列出具体哪几笔',3,
            {'kind':'analysis','tools':[{'tool':'statement_filter','args':{'month':'2026-09','category':'餐饮','direction':'支出','minAmountMinor':10000}}]},
            '逐笔：{{%s.transactions.0.date}} {{%s.transactions.0.merchant}} {{%s.transactions.0.amount_minor}}；{{%s.transactions.1.date}} {{%s.transactions.1.merchant}} {{%s.transactions.1.amount_minor}}。'%(over,over,over,over,over,over))
        october='statement_filter_2026_10_expense_min10000_餐饮'
        self.ask('换成10月',4,
            {'kind':'analysis','tools':[{'tool':'statement_filter','args':{'month':'2026-10','category':'餐饮','direction':'支出','minAmountMinor':10000}}]},
            '2026-10 餐饮支出合计{{%s.expense_minor}}，共{{%s.matched_count}}笔。'%(october,october))
        income='statement_filter_2026_10_income_min10000_餐饮'
        self.ask('改成收入',5,
            {'kind':'analysis','tools':[{'tool':'statement_filter','args':{'month':'2026-10','category':'餐饮','direction':'收入','minAmountMinor':10000}}]},
            '该类别在2026-10没有收入记录：{{%s.matched_count}}笔，合计{{%s.income_minor}}；要改看全部类别吗？'%(income,income))
        self.assertEqual(self.bank.args[0],{'month':'2026-09','category':'餐饮','direction':'支出','minAmountMinor':0})
        self.assertEqual(self.bank.args[1],{'month':'2026-09','category':'餐饮','direction':'支出','minAmountMinor':10000},'只改金额下限')
        self.assertEqual(self.bank.args[2],self.bank.args[1],'列明细沿用同条件，不换成整月')
        self.assertEqual(self.bank.args[3],{'month':'2026-10','category':'餐饮','direction':'支出','minAmountMinor':10000},'只改月份')
        self.assertEqual(self.bank.args[4],{'month':'2026-10','category':'餐饮','direction':'收入','minAmountMinor':10000},'只改方向，金额下限保留')
        plans=self.plans()
        self.assertEqual(plans[1]['previous_plan']['tools'][0]['args'],self.bank.args[0])
        self.assertEqual(plans[4]['previous_plan']['tools'][0]['args'],self.bank.args[3])
        drafts=[payload for payload in self.model.payloads if 'facts' in payload]
        self.assertIn(over+'.transactions.0.merchant',drafts[1]['facts'],'第三轮回答器拿到真实逐笔字段')
        self.assertIn(income+'.income_minor',drafts[4]['facts'],'收入轮事实与方向一致')
        self.assertNotIn(over+'.expense_minor',drafts[4]['facts'],'收入轮不混入支出合计')
    def test_answer_cannot_cite_facts_outside_the_turn(self):
        saved=self.ask('查2026年9月餐饮支出',1,
            {'kind':'analysis','tools':[{'tool':'statement_filter','args':{'month':'2026-09','category':'餐饮','direction':'支出','minAmountMinor':0}}]},
            '合计{{statement_filter_2026_09_expense_min10000_餐饮.expense_minor}}。',expect_ready=False)
        self.assertNotEqual(saved['state'],'RESULT_READY','引用本轮不存在的事实不会被放行')
    def test_fresh_session_without_conditions_asks_instead_of_guessing(self):
        self.model.responses=[{'kind':'clarification','tools':[],'question':'请先告诉我要看哪个月、哪个类别。'}]
        self.request.update(text='只看超过100元的',expectedVersion=1,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,modelRequestLimit=2)
        run=self.runtime.accept(self.request);self.runtime.execute(run['id']);saved=self.store.get(run['id'])
        self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'))
        self.assertEqual(saved['result_kind'],'clarification')
        self.assertEqual(self.bank.calls,0,'没有前序条件时不得静默查询')
        plan=self.plans()[0]
        self.assertEqual(plan.get('previous_plan'),{})
        self.assertEqual(plan.get('previous_question'),'')
