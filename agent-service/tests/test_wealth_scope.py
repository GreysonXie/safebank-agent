import unittest
from safebank_agent.contracts import ProtocolError
from safebank_agent.graph import planning

class WealthScopeTest(unittest.TestCase):
    def test_catalog_scope_accepts_empty_args(self):
        plan={'kind':'analysis','tools':[{'tool':'wealth_products','args':{}}]}
        self.assertEqual(planning(plan),plan)
    def test_catalog_scope_rejects_any_argument(self):
        for args in ({'month':'2026-09'},{'productIds':['DEMO-WM-01']},{'id':'DEMO-WM-01'}):
            with self.assertRaises(ProtocolError) as raised:
                planning({'kind':'analysis','tools':[{'tool':'wealth_products','args':args}]})
            self.assertEqual(str(raised.exception),'INVALID_TOOL_SCOPE')
