import json,uuid
from pathlib import Path
from .test_answer_fixture import CapturedAnswerTest,JavaBank
from .test_graph_runtime import Model
class RefundRewriteTest(CapturedAnswerTest):
 def run_refund(self,limit,invalid_twice=False):
  from safebank_agent.store import Store
  from safebank_agent.runtime import Runtime
  folder=Path(__file__).parent/'fixtures';fixture=json.loads((folder/'20261011_refund_failure.json').read_text(encoding='utf-8-sig'))
  bank=JavaBank(folder,'20261011_refund_failure.json');model=Model();payloads=[]
  original_complete=model.complete
  def capture(payload):payloads.append(payload);return original_complete(payload)
  model.complete=capture
  good={'kind':'analysis','answer':'不能据此说明实际消费减少。{{statement_2026_09.transactions.1.merchant}}入账{{statement_2026_09.transactions.1.amount_minor}}，属于退款；它增加入账和净结余，不抵减账单支出{{statement_2026_09.expense_minor}}。账单没有提供退款与原订单的关联，需核对原订单和退款凭证。'}
  model.responses=[{'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09'}}]},fixture['answer'],fixture['answer'] if invalid_twice else good]
  store=Store(self.config);runtime=Runtime(store,self.config,bank,model)
  try:
   run=runtime.accept(dict(userId='u1',conversationId=uuid.uuid4().hex,turnId=uuid.uuid4().hex,requestId=uuid.uuid4().hex,expectedVersion=1,text='2026-09账单里的退款，能说明实际消费减少了吗？',grant='fixture-only',modelRequestLimit=limit));runtime.execute(run['id']);saved=store.get(run['id'])
   with store.transaction() as cur:
    cur.execute("SELECT result_json FROM agent_effect_receipts WHERE origin_run_id=%s AND node_name='draft' AND state='SUCCEEDED'",(run['id'],));receipt=json.loads(cur.fetchone()['result_json'])
   self.assertEqual(receipt['content'],fixture['answer'])
   return saved,model.calls,bank.rendered,payloads
  finally:runtime.close();bank.close()
 def test_captured_invalid_refund_is_rewritten_once_and_bank_verified(self):
  saved,calls,text,payloads=self.run_refund(3)
  self.assertEqual(saved['state'],'RESULT_READY',saved.get('error_code'));self.assertEqual(calls,3)
  self.assertIn('3,200.00',text);self.assertIn('5,369.33',text);self.assertNotIn('设备维修',text)
  context=json.loads(payloads[-1]['messages'][-1]['content']);self.assertTrue(context['validation_error']);self.assertIn('transaction_rows',context)
 def test_per_turn_two_request_limit_blocks_rewrite_before_call(self):
  saved,calls,text,_=self.run_refund(2);self.assertEqual(calls,2);self.assertEqual(saved['error_code'],'ANSWER_REPAIR_BUDGET_EXHAUSTED');self.assertEqual(text,'')
 def test_second_invalid_draft_does_not_start_another_rewrite(self):
  saved,calls,text,_=self.run_refund(3,True);self.assertEqual(calls,3);self.assertEqual(saved['error_code'],'ANSWER_REPAIR_EXHAUSTED');self.assertEqual(text,'')
