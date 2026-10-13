import copy,json,unittest
from pathlib import Path
from safebank_agent.graph import build
from safebank_agent.models import MockModel

class PresentationTest(unittest.TestCase):
 def setUp(self):
  self.facts=json.loads((Path(__file__).parent/'fixtures/20261011_mandate_catalog.json').read_text(encoding='utf-8'))['facts']
 def payload(self,repair=0):
  class Context:
   def model(self,state,name,index,payload):self.payload=payload;return MockModel().complete(payload)
  context=Context();before=copy.deepcopy(self.facts)
  answer=build(None,context).nodes['draft'].invoke({'request':{'text':'所有代扣的收费周期和合同边界'},'receipts':[{'facts':self.facts,'data':[]}],'repair':repair})['answer']
  self.assertEqual(self.facts,before)
  return json.loads(context.payload['messages'][-1]['content']),answer
 def test_raw_enums_are_not_answer_references_in_draft_or_repair(self):
  for repair in (0,1):
   payload,answer=self.payload(repair)
   for row in payload['mandate_rows']:
    for field in ('status','billing_interval','contract_status'):
     self.assertNotIn(field,row['fields'])
     self.assertNotIn(row['rowId']+'.'+field,payload['facts'])
   self.assertIn('按季度',answer['answer'])
 def test_classification_and_chinese_cycle_survive_without_raw_references(self):
  payload,_=self.payload();rows={r['rowId']:r for r in payload['mandate_rows']}
  self.assertEqual(rows['mandates_latest.2']['billingDescription'],'约定按季度收费')
  self.assertEqual(rows['mandates_latest.0']['billingDescription'],'收费周期未确认')
  self.assertEqual(rows['mandates_latest.3']['monthlyTotalMembership'],'excluded')
  self.assertEqual({k for k,r in rows.items() if r['monthlyTotalMembership']=='included'},{'mandates_latest.1','mandates_latest.4'})
 def test_observation_time_only_present_when_asked(self):
  payload,_=self.payload();self.assertNotIn('mandates_latest.observation.queriedAt',payload['facts'])
  from safebank_agent.graph import answer_catalog
  facts,_=answer_catalog(self.facts,'这些授权查询时间是什么时候？')
  self.assertIn('mandates_latest.observation.queriedAt',facts)

