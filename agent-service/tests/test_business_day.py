"""真实日期口径：模型必须知道“今天”，才能把本月/上个月换算成账单工具的明确月份。"""
import datetime
import unittest

from safebank_agent.prompts import ANSWER, PLAN, business_day_clause


class BusinessDayClauseTest(unittest.TestCase):
    def test_clause_carries_real_date_and_month(self):
        clause = business_day_clause(datetime.date(2026, 10, 31))
        self.assertIn('2026-10-31', clause)
        self.assertIn('当前月份是 2026-10', clause)
        self.assertIn('上个月是 2026-09', clause)
        self.assertIn('亚洲'.replace('亚洲', 'Asia/Shanghai'), clause)

    def test_previous_month_crosses_year_boundary(self):
        clause = business_day_clause(datetime.date(2027, 1, 3))
        self.assertIn('当前月份是 2027-01', clause)
        self.assertIn('上个月是 2026-12', clause)

    def test_clause_forbids_substituting_another_month(self):
        clause = business_day_clause(datetime.date(2026, 3, 1))
        self.assertIn('不能改用其他月份代替', clause)
        self.assertIn('不要写进回答正文', clause)

    def test_prompts_do_not_hardcode_a_historical_month(self):
        self.assertNotIn('2026-09', PLAN)
        self.assertNotIn('2026-09', ANSWER)

    def test_default_uses_real_clock(self):
        today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()
        clause = business_day_clause()
        self.assertIn(f'当前月份是 {today.year:04d}-{today.month:02d}', clause)


if __name__ == '__main__':
    unittest.main()
