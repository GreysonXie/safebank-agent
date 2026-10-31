"""实体链接的确定性测试：隔离标记、口语指代、重名歧义、绝不默认取第一个。

候选全部用合成数据（与本项目"仅本地合成演示"的口径一致）。
"""

import unittest

from safebank_agent.entities import Candidate, best, link, normalize

MANDATES = [
    Candidate('m1', '视频服务（合成）', ('爱奇艺', '视频会员'), 'mandate'),
    Candidate('m2', '音乐服务（合成）', ('网易云音乐',), 'mandate'),
    Candidate('m3', '季度安全服务（合成）', (), 'mandate'),
]
ACCOUNTS = [
    Candidate('a1', '工资卡 · 储蓄账户', ('工资卡', '发工资那张'), 'account'),
    Candidate('a2', '日常消费账户', ('日常卡',), 'account'),
]
RECIPIENTS = [
    Candidate('r1', '张三 TEST-3271', ('同学',), 'recipient'),
    Candidate('r2', '张三 TEST-8862', ('同事',), 'recipient'),
]


class NormalizeTest(unittest.TestCase):
    def test_strips_quarantine_markers_and_demo_tags(self):
        self.assertEqual(normalize('〔视频服务（合成）〕'), '视频服务')
        self.assertEqual(normalize('〔社区餐厅〔南区分店〕〕'), '社区餐厅南区分店')

    def test_strips_punctuation_and_case(self):
        self.assertEqual(normalize('VIP 会员 A'), 'vip会员a')


class ExactAndAliasTest(unittest.TestCase):
    def test_exact_after_normalize(self):
        found = link('视频服务', MANDATES)
        self.assertEqual(found.best.id, 'm1')
        self.assertEqual(found.matches[0].how, 'exact')
        self.assertFalse(found.ambiguous)

    def test_alias(self):
        found = link('爱奇艺', MANDATES)
        self.assertEqual(found.best.id, 'm1')
        self.assertIn(found.matches[0].how, ('exact', 'alias', 'contains'))

    def test_account_alias(self):
        self.assertEqual(best('发工资那张', ACCOUNTS).id, 'a1')


class ColloquialReferenceTest(unittest.TestCase):
    def test_demonstrative_is_stripped(self):
        found = link('那个视频会员', MANDATES)
        self.assertEqual(found.matches[0].candidate.id, 'm1')

    def test_partial_name_still_resolves(self):
        self.assertEqual(best('季度安全', MANDATES).id, 'm3')
        self.assertEqual(best('音乐', MANDATES).id, 'm2')

    def test_relation_word_is_not_resolved(self):
        found = link('给我闺女转五百', RECIPIENTS)
        self.assertEqual(found.matches, ())
        self.assertIsNone(found.best)
        self.assertIn('补全名称', found.reason)


class AmbiguityTest(unittest.TestCase):
    def test_duplicate_names_never_pick_first(self):
        found = link('张三', RECIPIENTS)
        self.assertTrue(found.ambiguous)
        self.assertIsNone(found.best)
        self.assertEqual({item.candidate.id for item in found.matches}, {'r1', 'r2'})
        self.assertIn('指定', found.reason)

    def test_note_disambiguates_duplicates(self):
        # 备注别名出现在话里时可以唯一指认；只写姓名则必须澄清
        self.assertEqual(best('同事张三', RECIPIENTS).id, 'r2')
        self.assertEqual(best('同学', RECIPIENTS).id, 'r1')

    def test_weak_prefix_overlap_asks_instead_of_guessing(self):
        found = link('视频会员费', [Candidate('x1', '视频服务（合成）'), Candidate('x2', '音频服务（合成）')])
        self.assertTrue(found.ambiguous)
        self.assertIsNone(found.best)

    def test_no_shared_prefix_means_no_match(self):
        self.assertEqual(link('老张', RECIPIENTS).matches, ())


class NonNameTest(unittest.TestCase):
    def test_phone_number_matches_exactly(self):
        people = [Candidate('p1', '王小明 13800001234', (), 'recipient')]
        self.assertEqual(best('13800001234', people).id, 'p1')

    def test_empty_query_is_not_a_match(self):
        found = link('   ', MANDATES)
        self.assertEqual(found.matches, ())
        self.assertIsNone(found.best)

    def test_unknown_candidate_list_is_safe(self):
        self.assertEqual(link('视频服务', []).matches, ())
        self.assertIsNone(best('视频服务', None))


if __name__ == '__main__':
    unittest.main()
