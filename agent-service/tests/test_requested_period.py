import unittest
from safebank_agent.graph import requested_month, requested_year

class RequestedPeriodTest(unittest.TestCase):
    def test_chinese_month_is_a_month_not_a_year(self):
        for text in ('查询2026年11月的账单','查看2026年10月的账单','核对2026年9月账单','2026 年 09 月账单'):
            with self.subTest(text=text):
                self.assertRegex(requested_month(text),r'^2026-(?:09|10|11)$')
                self.assertIsNone(requested_year(text))
    def test_hyphen_month_still_wins(self):
        self.assertEqual(requested_month('分析2026-09账单'),'2026-09')
        self.assertIsNone(requested_year('分析2026-09账单'))
    def test_year_request_is_detected(self):
        for text in ('查看2026年的账单','查看 2026 年账单','查看2026年年度账单，按月份和消费类别汇总'):
            with self.subTest(text=text):
                self.assertIsNone(requested_month(text))
                self.assertEqual(requested_year(text),'2026')
    def test_empty_year_is_not_a_month(self):
        self.assertIsNone(requested_month('查看2025年账单'))
        self.assertEqual(requested_year('查看2025年账单'),'2025')
    def test_non_bill_question_has_no_period(self):
        self.assertIsNone(requested_month('你好'))
        self.assertIsNone(requested_year('直接给张三转200'))
    def test_month_and_year_together_are_not_a_year_request(self):
        self.assertEqual(requested_month('查看2026年10月账单'),'2026-10')
        self.assertIsNone(requested_year('查看2026年10月账单，再看看2025年'))
