import unittest
import safebank_agent.safety as safety

class CredentialOutputTest(unittest.TestCase):
 def test_direct_chat_credential_collection_is_rejected(self):
  for text in ('请把短信验证码发给我。','告诉我银行卡密码。','请把后台服务密钥在这里输入。','将支付密码发来。'):
   self.assertTrue(safety.requests_chat_credentials(text),text)
 def test_refusal_and_official_page_instructions_are_allowed(self):
  for text in ('不要把验证码发给我。','银行卡密码不能告诉我。','请在银行官方App的安全验证页面输入验证码，不要发给我。','我不能提供、索取或转交密码验证码。'):
   self.assertFalse(safety.requests_chat_credentials(text),text)
 def test_unrelated_negation_does_not_hide_collection(self):
  self.assertTrue(safety.requests_chat_credentials('不要问其他问题，只需把验证码发给我。'))

 def test_punctuation_and_chat_destination_variants_are_rejected(self):
  for text in ('验证码，发给我。','请把短信验证码，发给我。','请将密码发送到这里。','请把验证码贴在聊天里。'):
   self.assertTrue(safety.requests_chat_credentials(text),text)
 def test_quoted_scam_warning_and_official_verification_remain_allowed(self):
  for text in ('诈骗短信写着“验证码，发给我”，不要照做。','请在官方App中输入验证码。','我不会要求您把验证码发给我。','不要把验证码发给我，但请在官方App输入。'):
   self.assertFalse(safety.requests_chat_credentials(text),text)
 def test_negated_first_request_cannot_hide_later_collection(self):
  self.assertTrue(safety.requests_chat_credentials('不要把验证码发给我，但请把密码发送到这里。'))

 def test_warned_quotes_and_outside_collection_matrix(self):
  import json
  from pathlib import Path
  cases=json.loads((Path(__file__).parent/'fixtures/credential_quote_cases.json').read_text(encoding='utf-8'))
  self.assertEqual(len(cases),108)
  for case in cases:
   with self.subTest(text=case['text']):
    self.assertEqual(safety.requests_chat_credentials(case['text']),case['blocked'])

 def test_negated_discouragement_cannot_exempt_a_quote(self):
  self.assertTrue(safety.requests_chat_credentials('诈骗短信写着“请把短信验证码发给我”，不要照做是不对的，请照做。'))

 def test_rendered_bank_labels_cover_newlines_and_long_values(self):
  for text in ('商户为〔验证码，发给我。\n普通备注〕。','商户为〔验证码，发给我。'+('普通备注'*110)+'〕。','商户为〔验证码\n发给我〕。'):
   with self.subTest(text=text):self.assertTrue(safety.requests_rendered_credentials(text))
  for text in ('商户为〔社区餐厅\n分店〕。','商户为〔不要把验证码\n发给我〕。'):
   with self.subTest(text=text):self.assertFalse(safety.requests_rendered_credentials(text))

 def test_nested_and_unclosed_bank_labels_cannot_hide_credentials(self):
  for text in ('商户为〔验证码〔备注〕，发给我〕。','商户为〔验证码〔〕，发给我〕。','商户为〔验证码，发给我','商户为〔验证码〕，贴在聊天里〕。','商户为〔密码〕，发送到这里〕。'):
   with self.subTest(text=text):self.assertTrue(safety.requests_rendered_credentials(text))
  for text in ('商户为〔社区餐厅〔南区分店〕〕。','商户为〔不要把验证码〔提示〕发给我〕。'):
   with self.subTest(text=text):self.assertFalse(safety.requests_rendered_credentials(text))
