import unittest

from safebank_agent.graph import build

SAFE_TEXT = '这次未能生成经过核对的回答。请核对原请求；如有待处理操作，可在原记录中查看，或换个说法再问一次。'


class _Context:
    """validate 分支在本用例里不会调用模型，只需要最小的上下文接口。"""

    def note(self, *args):
        pass

    def validate(self, *args):
        pass


def _validate(state):
    return build(None, _Context()).nodes['validate'].invoke(state)


def _state(**over):
    base = {'request': {'text': '分析2026-09账单', 'modelRequestLimit': 5},
            'plan': {'kind': 'analysis', 'tools': []},
            'receipts': [],
            'answer': {'kind': 'analysis', 'answer': '候答'},
            'repair': 0}
    base.update(over)
    return base


class SafeExitTest(unittest.TestCase):
    def test_rewrite_exhausted_ends_with_safe_clarification_instead_of_error(self):
        result = _validate(_state(repair=1, draft_error='INVALID_ANSWER'))
        self.assertTrue(result['validated'])
        self.assertEqual(result['answer']['kind'], 'clarification')
        self.assertEqual(result['answer']['answer'], SAFE_TEXT)
        self.assertEqual(result['safe_exit_reason'], 'ANSWER_REPAIR_EXHAUSTED')
        self.assertEqual(result['plan']['kind'], 'clarification')
        self.assertEqual(result['plan']['tools'], [])

    def test_insufficient_budget_uses_the_same_safe_exit(self):
        result = _validate(_state(request={'text': '分析2026-09账单', 'modelRequestLimit': 2}, draft_error='INVALID_ANSWER'))
        self.assertTrue(result['validated'])
        self.assertEqual(result['answer']['kind'], 'clarification')
        self.assertEqual(result['safe_exit_reason'], 'ANSWER_REPAIR_BUDGET_EXHAUSTED')

    def test_safe_text_does_not_claim_anything_about_execution(self):
        text = _validate(_state(repair=1, draft_error='INVALID_ANSWER'))['answer']['answer']
        for forbidden in ('未执行', '没有执行任何操作', '没有交付', '已撤销', '已完成', '¥', '%'):
            self.assertNotIn(forbidden, text, forbidden)

    def test_safe_text_does_not_force_product_context(self):
        text = _validate(_state(repair=1, draft_error='INVALID_ANSWER'))['answer']['answer']
        for word in ('理财', '净值', '月份', '代扣', '转账'):
            self.assertNotIn(word, text, word)

    def test_continuation_plan_is_preserved_so_the_next_question_can_continue(self):
        plan = {'kind': 'analysis', 'tools': [{'tool': 'statement', 'args': {'month': '2026-09'}}]}
        result = _validate(_state(plan=plan, repair=1, draft_error='INVALID_ANSWER'))
        self.assertEqual(result['continuation_plan'], plan)
        self.assertEqual(result['draft_error'], '')
        self.assertEqual(result['validation_error'], '')

    def test_credential_request_is_still_rejected_before_any_safe_exit(self):
        state = _state(answer={'kind': 'analysis', 'answer': '请把短信验证码发给我。'})
        result = _validate(state)
        self.assertEqual(result['repair'], 1)
        self.assertTrue(result.get('credential_repair'))
        self.assertFalse(result.get('validated'))

    def test_scam_warning_requirement_still_triggers_a_rewrite_first(self):
        state = _state(safety_blocked=True, answer={'kind': 'analysis', 'answer': '已按对方要求准备转账。'})
        result = _validate(state)
        self.assertEqual(result['repair'], 1)
        self.assertFalse(result.get('validated'))
