import unittest

from safebank_agent.contracts import ProtocolError
from safebank_agent.graph import compact_facts, plan_rejection_tools, planning


class StatementQueryPlanTest(unittest.TestCase):
    """通用账单查询的工具契约：闭集键、日期格式、枚举、上限，以及目录预算优先级。"""

    def accepts(self, args):
        value = planning({'kind': 'analysis', 'tools': [{'tool': 'statement_query', 'args': args}]})
        self.assertEqual(value['tools'][0]['args'], args)
        return value

    def rejects(self, args):
        with self.assertRaises(ProtocolError):
            planning({'kind': 'analysis', 'tools': [{'tool': 'statement_query', 'args': args}]})

    def test_minimal_and_full_payloads_are_accepted(self):
        self.accepts({'start': '2026-08-01', 'end': '2026-10-29'})
        self.accepts({'start': '2026-08-01', 'end': '2026-10-29', 'bucket': 'week', 'groupBy': 'account',
                      'compare': 'previous_period', 'topN': 5, 'rows': {'offset': 0, 'limit': 10},
                      'filter': {'direction': '支出', 'categories': ['餐饮'], 'merchants': ['星巴克'],
                                 'accounts': ['checking'], 'minAmountMinor': 0, 'maxAmountMinor': 100000,
                                 'excludeInternal': True}})
        self.accepts({'start': '2026-01-01', 'end': '2026-12-31', 'bucket': 'month', 'rows': False})

    def test_only_closed_keys_and_type_safety(self):
        for args in (
            {'start': '2026-08-01', 'end': '2026-10-29', 'sql': 'select 1'},
            {'start': '2026-08-01'},
            {'start': '2026-8-1', 'end': '2026-10-29'},
            {'start': '2026-08-01', 'end': '2026-10-29', 'bucket': 'hour'},
            {'start': '2026-08-01', 'end': '2026-10-29', 'groupBy': 'card'},
            {'start': '2026-08-01', 'end': '2026-10-29', 'compare': 'last_month'},
            {'start': '2026-08-01', 'end': '2026-10-29', 'topN': 0},
            {'start': '2026-08-01', 'end': '2026-10-29', 'topN': True},
            {'start': '2026-08-01', 'end': '2026-10-29', 'rows': {'limit': -1}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'rows': {'page': 2}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'filter': {'merchant': 'x'}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'filter': {'categories': []}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'filter': {'minAmountMinor': -1}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'filter': {'direction': '支出中'}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'filter': {'excludeInternal': 'yes'}},
            {'start': '2026-08-01', 'end': '2026-10-29', 'userId': 'u2'},
        ):
            with self.subTest(args=args):
                self.rejects(args)

    def test_null_placeholders_mean_absent_instead_of_invalid(self):
        self.accepts({'start': '2026-08-01', 'end': '2026-10-29', 'bucket': None, 'groupBy': None, 'compare': None,
                      'topN': None, 'rows': None,
                      'filter': {'direction': None, 'categories': None, 'merchants': None, 'accounts': None,
                                 'minAmountMinor': None, 'maxAmountMinor': None, 'excludeInternal': None}})
        self.accepts({'start': '2026-08-01', 'end': '2026-10-29', 'rows': {'offset': None, 'limit': None}})

    def test_rejection_logging_carries_tool_names_only(self):
        names = plan_rejection_tools({'kind': 'analysis', 'tools': [{'tool': 'statement', 'args': {'month': '2026-09'}}]})
        self.assertEqual(names, 'tools=statement')
        self.assertEqual(plan_rejection_tools({'kind': 'general'}), '')
        self.assertEqual(plan_rejection_tools('not a plan'), '')

    def test_comparison_facts_outrank_buckets_in_the_catalog(self):
        prefix = 'statement_range_2026_07_01_2026_09_30_month'
        facts = {
            prefix + '.comparison.baseline.expense_minor': {'display': '11090.75元', 'number': 1109075, 'kind': 'MONEY'},
            prefix + '.comparison.groups.0.difference_minor': {'display': '3200.00元', 'number': 320000, 'kind': 'MONEY'},
            prefix + '.totals.expense_minor': {'display': '18224.45元', 'number': 1822445, 'kind': 'MONEY'},
        }
        for index in range(60):
            for metric in ('expense_minor', 'income_minor', 'net_minor', 'internal_transfer_minor', 'transaction_count'):
                facts['%s.buckets.%d.metrics.%s' % (prefix, index, metric)] = {'display': '9.00', 'number': 900, 'kind': 'MONEY'}
        focused = compact_facts(facts, '这季度比上季度支出多在哪')
        for key in (prefix + '.comparison.baseline.expense_minor', prefix + '.comparison.groups.0.difference_minor',
                    prefix + '.totals.expense_minor'):
            self.assertIn(key, focused, '对照段与总计不能被目录预算挤掉')

    def test_adjacent_duplicate_amounts_collapse(self):
        from safebank_agent.answers import clean_display_duplicates
        answer = {'kind': 'analysis', 'answer': '同期支出合计30,125.20元 30,125.20元，另有其余14项。'}
        cleaned = clean_display_duplicates(answer, {})['answer']
        self.assertEqual(cleaned, '同期支出合计30,125.20元，另有其余14项。')

    def test_other_tools_keep_their_exact_argument_sets(self):
        for tool, args in (('statement', {'month': '2026-09'}), ('statement_compare', {'month': '2026-09', 'comparisonMonth': '2026-08'}),
                           ('account_query', {}), ('recurring_analysis', {})):
            planning({'kind': 'analysis', 'tools': [{'tool': tool, 'args': args}]})
        with self.assertRaises(ProtocolError):
            planning({'kind': 'analysis', 'tools': [{'tool': 'statement', 'args': {'start': '2026-08-01'}}]})
        with self.assertRaises(ProtocolError):
            planning({'kind': 'analysis', 'tools': [{'tool': 'statement_query_unlisted', 'args': {'start': '2026-08-01', 'end': '2026-08-31'}}]})

    def test_range_totals_survive_catalog_compaction(self):
        prefix = 'statement_range_2026_08_01_2026_10_29_day'
        facts = {prefix + '.totals.expense_minor': {'display': '1.00', 'number': 100, 'kind': 'MONEY'}}
        for index in range(60):
            for metric in ('expense_minor', 'income_minor', 'net_minor', 'internal_transfer_minor', 'transaction_count'):
                facts['%s.buckets.%d.metrics.%s' % (prefix, index, metric)] = {'display': '9.00', 'number': 900, 'kind': 'MONEY'}
        focused = compact_facts(facts, '这个月每天花了多少')
        self.assertIn(prefix + '.totals.expense_minor', focused, '总计优先于分桶，不能被目录预算挤掉')
        self.assertLess(len(focused), len(facts), '目录有硬上限，超出部分必须裁掉而不是全量塞给模型')


if __name__ == '__main__':
    unittest.main()
