import copy,json,unittest
from pathlib import Path
from safebank_agent.contracts import ProtocolError
from safebank_agent.task_plan import validate_task_plan

def proposal():
    return json.loads((Path(__file__).parent/'fixtures/savings_task_plan.json').read_text(encoding='utf-8'))

class TaskPlanTest(unittest.TestCase):
    def test_valid_unapproved_plan(self):
        self.assertEqual(validate_task_plan(proposal()),proposal())
    def test_unsafe_variants(self):
        variants=[]
        p=proposal();p['nodes'][0]['dependsOn']=['t'];variants.append(p)
        p=proposal();p['nodes'][-1]['dependsOn']=['missing'];variants.append(p)
        p=proposal();p['nodes'][3]['params']['amountMinor']=100;variants.append(p)
        p=proposal();p['nodes'][3]['type']='execute_transfer';variants.append(p)
        p=proposal();p['nodes'][4]['dependsOn']=['r1'];variants.append(p)
        p=proposal();p['nodes'][3]['params']['merchantQuery']='音乐服务';variants.append(p)
        p=proposal();p['nodes'][0]['authorized']=True;variants.append(p)
        p=proposal();p['nodes'][2]['dependsOn']=['m','m'];variants.append(p)
        p=proposal();p['schemaVersion']=True;variants.append(p)
        for p in variants:
            with self.subTest(plan=p),self.assertRaises(ProtocolError):validate_task_plan(p)
    def test_revocation_limit(self):
        for count in (1,4,5):
            p=proposal();rs=[dict(id='r'+str(i),type='mandate_revoke',params={'merchantQuery':'服务'+str(i)},dependsOn=['m']) for i in range(count)]
            p['nodes']=p['nodes'][:2]+rs+p['nodes'][-2:]
            p['nodes'][-2]['dependsOn']=[r['id'] for r in rs]
            if count<=4:self.assertEqual(validate_task_plan(p),p)
            else:
                with self.assertRaises(ProtocolError):validate_task_plan(p)
