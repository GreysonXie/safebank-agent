import json,unittest
from unittest.mock import patch
from safebank_agent.config import Config
from safebank_agent.contracts import ProtocolError

class CandidateConfigTest(unittest.TestCase):
 def test_openai_compatible_gateway_uses_explicit_url_model_and_private_key(self):
  values={'SAFEBANK_AGENT_DB_CONFIG':'{}','SAFEBANK_BANK_URL':'http://127.0.0.1:8134','SAFEBANK_AGENT_SERVICE_KEY':'a'*64,'SAFEBANK_MODEL_MODE':'openai','SAFEBANK_MODEL_URL':'https://gateway.example/v1/','SAFEBANK_MODEL_NAME':'gpt-6.1-sol','SAFEBANK_MODEL_API_KEY':'fixture-private-key'}
  with patch.dict('os.environ',values,clear=True):config=Config.environment()
  self.assertEqual(config.model_mode,'openai');self.assertEqual(config.model_url,'https://gateway.example/v1')
  self.assertEqual(config.model_name,'gpt-6.1-sol');self.assertEqual(config.model_key,'fixture-private-key')
  self.assertNotIn('fixture-private-key',repr(config))
 def test_openai_gateway_rejects_missing_key_and_unsafe_endpoint(self):
  values={'SAFEBANK_AGENT_DB_CONFIG':'{}','SAFEBANK_BANK_URL':'http://127.0.0.1:8134','SAFEBANK_AGENT_SERVICE_KEY':'a'*64,'SAFEBANK_MODEL_MODE':'openai','SAFEBANK_MODEL_NAME':'requested','SAFEBANK_MODEL_API_KEY':'fixture-private-key'}
  for url in ('http://gateway.example/v1','https://key@gateway.example/v1','https://gateway.example/v1?key=private','https://gateway.example/v1#private','https:///v1','https://gateway.example:invalid/v1'):
   with self.subTest(url=url),patch.dict('os.environ',dict(values,SAFEBANK_MODEL_URL=url),clear=True),self.assertRaisesRegex(ProtocolError,'INVALID_MODEL_CONFIGURATION'):Config.environment()
  values.update(SAFEBANK_MODEL_URL='https://gateway.example/v1',SAFEBANK_MODEL_API_KEY='')
  with patch.dict('os.environ',values,clear=True),self.assertRaisesRegex(ProtocolError,'INVALID_MODEL_CONFIGURATION'):Config.environment()
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
