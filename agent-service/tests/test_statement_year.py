import unittest
from safebank_agent.contracts import ProtocolError
from safebank_agent.graph import planning

class StatementYearTest(unittest.TestCase):
    def test_year_plan_is_accepted(self):
        plan={'kind':'analysis','tools':[{'tool':'statement_year','args':{'year':'2026'}}]}
        self.assertEqual(planning(plan),plan)
    def test_invalid_year_is_rejected(self):
        for bad in ('26','2026年','2026-09','2026 ','٢٠٢٦'):
            with self.assertRaises(ProtocolError) as raised:
                planning({'kind':'analysis','tools':[{'tool':'statement_year','args':{'year':bad}}]})
            self.assertEqual(str(raised.exception),'INVALID_YEAR')
    def test_year_scope_needs_exact_args(self):
        for args in ({'year':'2026','month':'2026-09'},{'month':'2026-09'},{}):
            with self.assertRaises(ProtocolError) as raised:
                planning({'kind':'analysis','tools':[{'tool':'statement_year','args':args}]})
            self.assertEqual(str(raised.exception),'INVALID_TOOL_SCOPE')
    def test_month_scope_still_rejects_year_value(self):
        with self.assertRaises(ProtocolError) as raised:
            planning({'kind':'analysis','tools':[{'tool':'statement','args':{'month':'2026'}}]})
        self.assertEqual(str(raised.exception),'INVALID_MONTH')
