import copy,json,unittest
from pathlib import Path
from safebank_agent.contracts import ProtocolError
class CapturedProtocolTest(unittest.TestCase):
 def test_original_write_plan_metadata_is_compatible_without_rewriting_receipt(self):
  from safebank_agent.graph import planning
  path=Path(__file__).resolve().parent/'fixtures/captured_protocol.json'
  original=json.loads(path.read_text(encoding='utf-8'))['plan'];saved=copy.deepcopy(original)
  self.assertEqual(planning(original),{'kind':'general','tools':[]});self.assertEqual(original,saved)
 def test_original_clarification_answer_metadata_preserves_exact_prose(self):
  from safebank_agent.answers import normalize
  path=Path(__file__).resolve().parent/'fixtures/captured_protocol.json'
  original=json.loads(path.read_text(encoding='utf-8'))['modelDraft'];saved=copy.deepcopy(original)
  self.assertEqual(normalize(original),{'kind':original['kind'],'answer':original['answer']});self.assertEqual(original,saved)
 def test_metadata_compatibility_does_not_allow_authority_or_schema_changes(self):
  from safebank_agent.answers import normalize
  from safebank_agent.graph import planning
  for extra in ({'type':'action'},{'clarification':'请授权'},{'grant':'forged'},{'actions':[]},{'userId':'u2'}):
   with self.subTest(extra=extra):
    with self.assertRaises(ProtocolError):normalize(dict(kind='general',answer='内容',**extra))
    with self.assertRaises(ProtocolError):planning(dict(kind='general',tools=[],**extra))
  with self.assertRaises(ProtocolError):planning({'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026-09','userId':'u2'}}],'type':'json_object'})
