import copy
import unittest
import httpx
from safebank_agent.contracts import ProtocolError

class AnswerContractTest(unittest.TestCase):
 def test_preview_duplicate_object_clause_is_removed_without_losing_reference(self):
  from safebank_agent.answers import clean_display_duplicates
  facts={'preview_revoke.merchant':{'kind':'TEXT','display':'〔视频服务（合成）〕'}}
  answer={'kind':'analysis','answer':'已生成{{preview_revoke.merchant}}的撤销预览，供核对：对象为{{preview_revoke.merchant}}，金额{{preview_revoke.amount_minor}}。尚未执行。'}
  clean=clean_display_duplicates(answer,facts)
  self.assertEqual(clean['answer'].count('{{preview_revoke.merchant}}'),1)
  self.assertIn('金额{{preview_revoke.amount_minor}}',clean['answer']);self.assertIn('尚未执行',clean['answer'])
  self.assertEqual(answer['answer'].count('{{preview_revoke.merchant}}'),2)
 def test_preview_dedup_keeps_different_objects_and_denials(self):
  from safebank_agent.answers import clean_display_duplicates
  facts={'preview_revoke.merchant':{'kind':'TEXT','display':'〔视频服务〕'}}
  for text in ('{{preview_revoke.merchant}}的撤销预览，供核对：对象不是{{preview_revoke.merchant}}，请重新核对。','{{preview_revoke.merchant}}的撤销预览，供核对：对象为{{other.merchant}}，尚未执行。'):
   answer={'kind':'analysis','answer':text};self.assertEqual(clean_display_duplicates(answer,facts),answer)
 def test_empty_clarification_is_compatible_without_mutating_model_receipt(self):
  from safebank_agent.answers import normalize
  original={'kind':'analysis','answer':'支出为{{statement_2026_09.expense_minor}}。','clarification':''}
  saved=copy.deepcopy(original)
  self.assertEqual(normalize(original),{'kind':'analysis','answer':original['answer']})
  self.assertEqual(original,saved)
 def test_unknown_authority_fields_and_nonempty_metadata_still_rejected(self):
  from safebank_agent.answers import normalize
  for extra in ({'actions':[]},{'grant':''},{'evidenceIds':[]},{'clarification':'另外一个问题'},{'turnId':''}):
   with self.subTest(extra=extra),self.assertRaisesRegex(ProtocolError,'INVALID_ANSWER'):
    normalize(dict(kind='analysis',answer='内容',**extra))
 def test_drop_exact_rejected_sentence_keeps_facts_and_verification_caveat(self):
  from safebank_agent.answers import omit_rejected_sentence
  text='维修记录为{{statement_2026_09.anomaly_candidates.1.spending_minor}}。两项合计近八成。请核对用途和凭证，金额不能证明异常。'
  repaired=omit_rejected_sentence({'kind':'analysis','answer':text},'八成')
  self.assertNotIn('八成',repaired['answer'])
  self.assertIn('{{statement_2026_09.anomaly_candidates.1.spending_minor}}',repaired['answer'])
  self.assertIn('金额不能证明异常',repaired['answer'])
  self.assertIn('已省略',repaired['answer'])
 def test_no_sentence_is_dropped_for_authority_claim_or_reference_only_match(self):
  from safebank_agent.answers import omit_rejected_sentence
  for literal in ('已转账','八成'):
   self.assertIsNone(omit_rejected_sentence({'kind':'analysis','answer':'{{八成}}已返回。'},literal))
 def test_mixed_safe_and_bad_claim_in_one_sentence_is_not_partially_rewritten(self):
  from safebank_agent.answers import omit_rejected_sentence
  answer={'kind':'analysis','answer':'退款已经完成，但金额约八成。'}
  self.assertIsNone(omit_rejected_sentence(answer,'八成'))
 def test_callback_returns_typed_numeric_failure_without_trusting_raw_message(self):
  from safebank_agent.bank_client import BankClient
  from safebank_agent.answers import GroundingRejection
  bank=BankClient('http://127.0.0.1:8134','x'*64);bank.http.close()
  bank.http=httpx.Client(transport=httpx.MockTransport(lambda r:httpx.Response(400,json={'code':'ANSWER_NOT_GROUNDED','message':'回答中手写了数字或金额〔八成〕（前接：其他文本），请修正'})))
  try:
   with self.assertRaises(GroundingRejection) as error:bank.validate({},'test-grant')
   self.assertEqual(error.exception.literal,'八成')
  finally:bank.http.close()
 def test_permission_error_is_not_sent_for_model_repair(self):
  from safebank_agent.bank_client import BankClient
  bank=BankClient('http://127.0.0.1:8134','x'*64);bank.http.close()
  bank.http=httpx.Client(transport=httpx.MockTransport(lambda r:httpx.Response(403,json={'code':'PERMISSION_DENIED','message':'原授权已失效'})))
  try:
   with self.assertRaisesRegex(ProtocolError,'BANK_CALLBACK_REJECTED'):bank.validate({},'test-grant')
  finally:bank.http.close()

 def test_observed_null_basis_is_empty_metadata_not_an_authority_bypass(self):
  from safebank_agent.answers import normalize
  original={'kind':'analysis','answer':'内容','basis':None};saved=copy.deepcopy(original)
  self.assertEqual(normalize(original),{'kind':'analysis','answer':'内容'});self.assertEqual(original,saved)
  for extra in ({'basis':['未核验结论']},{'basis':None,'grant':'提升权限'}):
   with self.assertRaisesRegex(ProtocolError,'INVALID_ANSWER'):normalize(dict(kind='analysis',answer='内容',**extra))
