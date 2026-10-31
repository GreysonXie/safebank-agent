"""卡片与理财接入对话后的规划契约测试：只接受闭集参数，越界一律拒绝。

这一层保证"用户一句话能触发"的前提：模型给出的工具与参数必须落在合同里，
不会因为笔误或多加字段而静默变成别的业务。
"""

import unittest

from safebank_agent.graph import ProtocolError, card_preview_args, finance_args, planning, suggested_tools


def plan(tools):
    return planning({'kind': 'analysis', 'tools': tools})


class CardToolTest(unittest.TestCase):
    def test_read_tools_take_no_arguments(self):
        for tool in ('card_list', 'card_history'):
            with self.subTest(tool=tool):
                self.assertEqual(plan([{'tool': tool, 'args': {}}])['tools'][0]['tool'], tool)

    def test_lock_preview_with_card_query(self):
        planned = plan([{'tool': 'card_preview', 'args': {'kind': 'LOCK', 'cardQuery': '日常卡'}}])
        self.assertEqual(planned['tools'][0]['args']['kind'], 'LOCK')

    def test_apply_preview_full_arguments(self):
        args = {'kind': 'APPLY', 'accountQuery': '日常消费账户', 'label': '网购虚拟卡', 'cardType': 'VIRTUAL',
                'perTransactionLimitMinor': 100000, 'dailyLimitMinor': 500000,
                'onlineEnabled': True, 'overseasEnabled': False}
        self.assertEqual(plan([{'tool': 'card_preview', 'args': args}])['tools'][0]['tool'], 'card_preview')

    def test_unknown_kind_is_rejected(self):
        with self.assertRaises(ProtocolError):
            card_preview_args({'kind': 'WIPE'})

    def test_extra_argument_is_rejected(self):
        with self.assertRaises(ProtocolError):
            card_preview_args({'kind': 'LOCK', 'cardQuery': '日常卡', 'cardId': 'card-1'})

    def test_wrong_types_are_rejected(self):
        for args in ({'kind': 'LOCK', 'perTransactionLimitMinor': '500'},
                     {'kind': 'UPDATE_CONTROLS', 'onlineEnabled': 'yes'},
                     {'kind': 'LOCK', 'cardQuery': ''}):
            with self.subTest(args=args):
                with self.assertRaises(ProtocolError):
                    card_preview_args(args)

    def test_preview_limit_is_one_per_plan(self):
        with self.assertRaises(ProtocolError):
            plan([{'tool': 'card_preview', 'args': {'kind': 'LOCK', 'cardQuery': '日常卡'}},
                  {'tool': 'card_preview', 'args': {'kind': 'REPORT_LOST', 'cardQuery': '日常卡'}}])

    def test_preview_cannot_be_mixed_with_transfer_preview(self):
        with self.assertRaises(ProtocolError):
            plan([{'tool': 'card_preview', 'args': {'kind': 'LOCK', 'cardQuery': '日常卡'}},
                  {'tool': 'transfer_preview',
                   'args': {'sourceQuery': '日常账户', 'recipientQuery': '同学', 'amountMinor': 10000}}])


class FinanceToolTest(unittest.TestCase):
    def test_full_preferences(self):
        args = {'riskPreference': 'CONSERVATIVE', 'purpose': 'RESERVE', 'liquidity': 'IMMEDIATE',
                'amountMinor': 5000000, 'horizonMonths': 12, 'capitalProtection': True}
        self.assertEqual(plan([{'tool': 'finance_recommend', 'args': args}])['tools'][0]['tool'], 'finance_recommend')

    def test_partial_preferences_are_allowed_for_clarification(self):
        # 缺项允许：由 Java 返回缺哪几项，规划器据此补问
        self.assertIsNone(finance_args({'amountMinor': 100000}))

    def test_unknown_enum_is_rejected(self):
        for args in ({'riskPreference': 'AGGRESSIVE'}, {'purpose': 'GAMBLE'}, {'liquidity': 'FOREVER'}):
            with self.subTest(args=args):
                with self.assertRaises(ProtocolError):
                    finance_args(args)

    def test_out_of_range_values_are_rejected(self):
        for args in ({'amountMinor': 99}, {'amountMinor': 10000000001}, {'horizonMonths': 0}, {'horizonMonths': 121}):
            with self.subTest(args=args):
                with self.assertRaises(ProtocolError):
                    finance_args(args)

    def test_empty_arguments_are_rejected(self):
        with self.assertRaises(ProtocolError):
            finance_args({})

    def test_extra_argument_is_rejected(self):
        with self.assertRaises(ProtocolError):
            finance_args({'amountMinor': 100000, 'riskLevel': 2})


class MixedPlanTest(unittest.TestCase):
    def test_card_read_then_preview_in_one_turn(self):
        planned = plan([{'tool': 'card_list', 'args': {}},
                        {'tool': 'card_preview', 'args': {'kind': 'REPORT_LOST', 'cardQuery': '工资卡'}}])
        self.assertEqual([item['tool'] for item in planned['tools']], ['card_list', 'card_preview'])

    def test_new_tools_still_capped_at_four(self):
        tools = [{'tool': 'card_list', 'args': {}}, {'tool': 'card_history', 'args': {}},
                 {'tool': 'account_query', 'args': {}}, {'tool': 'recurring_analysis', 'args': {}},
                 {'tool': 'mandate_receipt', 'args': {}}]
        with self.assertRaises(ProtocolError):
            plan(tools)


class SuggestedToolsTest(unittest.TestCase):
    """按用词给的工具建议：只是提示，用于减少"该选工具却回澄清"。"""

    def test_card_questions_hint_card_tools(self):
        for question in ('我都有什么卡', '把那张卡挂失', '每张卡的限额是多少', '开张虚拟卡网上买东西'):
            with self.subTest(question=question):
                self.assertIn('card_list', suggested_tools(question))

    def test_finance_question_hints_finance_tool(self):
        self.assertIn('finance_recommend', suggested_tools('我有5万闲钱，买点稳的，别亏就行'))

    def test_credit_card_repayment_still_hints_card_tools(self):
        # 还款本身未接入，但先让模型查到卡、再说明边界，比直接空转更好
        self.assertIn('card_list', suggested_tools('帮我把信用卡账单还了'))

    def test_statement_and_transfer_questions_have_no_card_hint(self):
        for question in ('上个月餐饮花了多少', '给同学转500', '我这个月扣了两回物业费'):
            with self.subTest(question=question):
                self.assertEqual([hint for hint in suggested_tools(question) if hint.startswith('card')], [])


if __name__ == '__main__':
    unittest.main()
