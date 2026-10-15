import json,unittest
from unittest.mock import patch
from safebank_agent.config import Config
from safebank_agent.contracts import ProtocolError

class CandidateConfigTest(unittest.TestCase):
 def test_default_model_uses_current_official_flash_name(self):
  self.assertEqual(self.config('http://127.0.0.1:8136').model_name,'deepseek-flash')
 def test_explicit_legacy_model_is_not_silently_rewritten(self):
  values={'SAFEBANK_AGENT_DB_CONFIG':'{}','SAFEBANK_BANK_URL':'http://127.0.0.1:8136','SAFEBANK_AGENT_SERVICE_KEY':'a'*64,'SAFEBANK_MODEL_NAME':'deepseek-v4-flash'}
  with patch.dict('os.environ',values,clear=True):self.assertEqual(Config.environment().model_name,'deepseek-v4-flash')
 def config(self,url):
  values={'SAFEBANK_AGENT_DB_CONFIG':json.dumps({'host':'127.0.0.1'}),'SAFEBANK_BANK_URL':url,'SAFEBANK_AGENT_SERVICE_KEY':'a'*64,'SAFEBANK_MODEL_MODE':'mock'}
  with patch.dict('os.environ',values,clear=True):return Config.environment()
 def test_original_and_separate_candidate_loopback_ports(self):
  self.assertEqual(self.config('http://127.0.0.1:8134').bank_url,'http://127.0.0.1:8134')
  self.assertEqual(self.config('http://127.0.0.1:8136').bank_url,'http://127.0.0.1:8136')
 def test_remote_or_unapproved_callback_is_rejected(self):
  for url in ('http://example.com:8136','http://127.0.0.1:9999','http://127.0.0.1:8136/redirect','https://127.0.0.1:8136'):
   with self.subTest(url=url),self.assertRaisesRegex(ProtocolError,'INVALID_CONFIGURATION'):self.config(url)
