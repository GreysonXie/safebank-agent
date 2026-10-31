"""旁路比对（影子模式）的测试：结论正确、日志无客户数据、绝不影响本轮。

纯逻辑部分本地可跑；最后一条验证"规划节点确实会写这行诊断"，需要 langgraph，
因此在容器/CI 环境执行。
"""

import contextlib
import io
import re
import unittest
from datetime import date

from safebank_agent import shadow

TODAY = date(2026, 10, 31)


def plan_query(**args):
    return {'kind': 'analysis', 'tools': [{'tool': 'statement_query', 'args': args}]}


def report(question, planned, today=TODAY):
    return shadow.inspect(question, planned, today)


class TimeComparisonTest(unittest.TestCase):
    def test_agreed(self):
        got = report('2026年9月1号到9月30号我花了多少',
                     plan_query(start='2026-09-01', end='2026-09-30', bucket='month'))
        self.assertEqual(got.time.code, shadow.AGREED)
        self.assertEqual(got.time.detail, '')

    def test_partial_when_model_narrows_inside_the_spoken_range(self):
        got = report('2026年9月1号到9月30号我花了多少',
                     plan_query(start='2026-09-01', end='2026-09-15'))
        self.assertEqual(got.time.code, shadow.PARTIAL)

    def test_mismatch_on_a_different_month(self):
        got = report('帮我看看上个月账单', plan_query(start='2026-08-01', end='2026-08-31'))
        self.assertEqual(got.time.code, shadow.MISMATCH)

    def test_agreed_for_relative_words(self):
        got = report('上个月账单', plan_query(start='2026-09-01', end='2026-09-30'))
        self.assertEqual(got.time.code, shadow.AGREED)

    def test_unparsed_when_question_has_no_time(self):
        got = report('钱都去哪了', plan_query(start='2026-09-01', end='2026-09-30'))
        self.assertEqual(got.time.code, shadow.UNPARSED)

    def test_none_when_plan_carries_no_range(self):
        got = report('上个月账单', {'kind': 'analysis', 'tools': [{'tool': 'account_query', 'args': {}}]})
        self.assertEqual(got.time.code, shadow.NONE)
        self.assertEqual(got.checked, 0)


class BucketComparisonTest(unittest.TestCase):
    def test_agreed(self):
        got = report('上个月账单', plan_query(start='2026-09-01', end='2026-09-30', bucket='month'))
        self.assertEqual(got.bucket.code, shadow.AGREED)

    def test_diff_when_model_uses_another_granularity(self):
        got = report('上个月账单', plan_query(start='2026-09-01', end='2026-09-30', bucket='day'))
        self.assertEqual(got.bucket.code, shadow.DIFF)

    def test_none_when_time_disagrees(self):
        got = report('上个月账单', plan_query(start='2026-08-01', end='2026-08-31', bucket='day'))
        self.assertEqual(got.bucket.code, shadow.NONE)


class MoneyComparisonTest(unittest.TestCase):
    def test_statement_threshold_agreed(self):
        planned = {'kind': 'analysis', 'tools': [{'tool': 'statement_query',
                                                  'args': {'start': '2026-09-01', 'end': '2026-09-30',
                                                           'filter': {'minAmountMinor': 50000}}}]}
        self.assertEqual(report('9月超过500的消费有哪些', planned).money.code, shadow.AGREED)

    def test_statement_threshold_mismatch(self):
        planned = {'kind': 'analysis', 'tools': [{'tool': 'statement_query',
                                                  'args': {'start': '2026-09-01', 'end': '2026-09-30',
                                                           'filter': {'minAmountMinor': 5000}}}]}
        self.assertEqual(report('9月超过500的消费有哪些', planned).money.code, shadow.MISMATCH)

    def test_transfer_amount_agreed_for_colloquial_number(self):
        planned = {'kind': 'analysis', 'tools': [{'tool': 'transfer_preview',
                                                  'args': {'amountMinor': 150000}}]}
        self.assertEqual(report('转一千五给他', planned).money.code, shadow.AGREED)

    def test_transfer_amount_mismatch_on_shorthand(self):
        planned = {'kind': 'analysis', 'tools': [{'tool': 'transfer_preview',
                                                  'args': {'amountMinor': 1505}}]}
        self.assertEqual(report('转一千五给他', planned).money.code, shadow.MISMATCH)

    def test_unparsed_when_plan_has_amount_but_words_do_not(self):
        planned = {'kind': 'analysis', 'tools': [{'tool': 'transfer_preview',
                                                  'args': {'amountMinor': 100000}}]}
        self.assertEqual(report('转点钱给他', planned).money.code, shadow.UNPARSED)

    def test_none_without_amounts(self):
        self.assertEqual(report('上个月账单', plan_query(start='2026-09-01', end='2026-09-30')).money.code,
                         shadow.NONE)


class LogLineTest(unittest.TestCase):
    def test_none_when_nothing_comparable(self):
        self.assertIsNone(report('你好', {'kind': 'general', 'tools': []}).line())

    def test_line_has_codes_and_tool_names_only(self):
        question = '2026年9月1号到9月30号我花了多少，超过500的'
        planned = plan_query(start='2026-09-01', end='2026-09-30', bucket='week',
                             filter={'minAmountMinor': 50000})
        line = report(question, planned).line()
        self.assertIn('time=AGREED', line)
        self.assertIn('bucket=DIFF', line)
        self.assertIn('money=AGREED', line)
        self.assertIn('tools=statement_query', line)
        # 不含客户原文片段、不含金额、不含日期，也不含任何数字
        for fragment in ('9月', '500', '花了', '2026'):
            self.assertNotIn(fragment, line)
        self.assertIsNone(re.search(r'\d', line), line)


class SafetyTest(unittest.TestCase):
    def test_emit_writes_one_line(self):
        stream = io.StringIO()
        line = shadow.emit('上个月账单', plan_query(start='2026-09-01', end='2026-09-30', bucket='month'),
                           TODAY, stream=stream)
        self.assertIsNotNone(line)
        self.assertEqual(stream.getvalue().count('\n'), 1)

    def test_emit_is_silent_when_nothing_to_compare(self):
        stream = io.StringIO()
        self.assertIsNone(shadow.emit('你好', {'kind': 'general', 'tools': []}, TODAY, stream=stream))
        self.assertEqual(stream.getvalue(), '')

    def test_emit_never_raises_on_garbage(self):
        for question, planned, today in [
            (None, None, TODAY),
            ('上个月', None, TODAY),
            ('上个月', {'tools': 'not-a-list'}, TODAY),
            ('上个月', plan_query(start='2026-09-01', end='2026-09-30'), None),
            ('上个月', {'kind': 'analysis', 'tools': [{'tool': 'statement_query', 'args': None}]}, TODAY),
            ('上个月', {'kind': 'analysis', 'tools': [{'tool': 'statement_query',
                                                      'args': {'start': 20260901, 'end': None}}]}, TODAY),
        ]:
            with self.subTest(question=question, planned=planned):
                self.assertIsNone(shadow.emit(question, planned, today, stream=io.StringIO()))

    def test_broken_stream_does_not_raise(self):
        class Broken:
            def write(self, _):
                raise RuntimeError('disk full')
        self.assertIsNone(shadow.emit('上个月', plan_query(start='2026-09-01', end='2026-09-30'),
                                      TODAY, stream=Broken()))


class PlanNodeHookTest(unittest.TestCase):
    """规划节点确实调用旁路比对，且诊断失败不影响本轮。"""

    def test_plan_node_emits_shadow_line_then_continues(self):
        try:
            from langgraph.checkpoint.memory import InMemorySaver
            from safebank_agent import graph as graph_module
        except Exception as error:  # 本地没装 langgraph 时跳过，容器/CI 会跑
            self.skipTest('langgraph 不可用：%s' % error)

        class StopHere(Exception):
            pass

        class Context:
            def __init__(self):
                self.plan_calls = 0
                self.tool_calls = 0

            def model(self, state, stage, retries, payload):
                self.plan_calls += 1
                return {'content': {'kind': 'analysis', 'tools': [{
                    'tool': 'statement_query',
                    'args': {'start': '2026-09-01', 'end': '2026-09-30', 'bucket': 'month'}}]}}

            def tool(self, state, index, item):
                self.tool_calls += 1
                raise StopHere()

        context = Context()
        app = graph_module.build(InMemorySaver(), context)
        buffer = io.StringIO()
        with contextlib.redirect_stderr(buffer), self.assertRaises(StopHere):
            app.invoke({'request': {'text': '2026年9月1号到9月30号我花了多少',
                                    'turnId': 'turn-shadow', 'expectedVersion': 1}},
                       {'configurable': {'thread_id': 'shadow-hook'}})
        text = buffer.getvalue()
        self.assertIn('NLU_SHADOW', text)
        self.assertIn('time=AGREED', text)
        self.assertEqual(context.plan_calls, 1)
        self.assertEqual(context.tool_calls, 1)


if __name__ == '__main__':
    unittest.main()
