"""时间解析的确定性测试：相对说法、缺年份推断、进行中区间截断、不支持项不编造。

业务日期固定为 2026-10-31（周六），与演示环境一致；跨年另用 2027-01-03 验证。
"""

import datetime
import unittest

from safebank_agent.timex import find, first, unsupported

TODAY = datetime.date(2026, 10, 31)
DAY = datetime.date


class RelativeWordTest(unittest.TestCase):
    def check(self, text, start, end, bucket, today=TODAY):
        timex = first(text, today)
        self.assertIsNotNone(timex, text)
        self.assertEqual((timex.start, timex.end, timex.bucket), (start, end, bucket), text)
        return timex

    def test_days(self):
        self.check('今天花了多少', DAY(2026, 10, 31), DAY(2026, 10, 31), 'day')
        self.check('昨天', DAY(2026, 10, 30), DAY(2026, 10, 30), 'day')
        self.check('前天', DAY(2026, 10, 29), DAY(2026, 10, 29), 'day')

    def test_weeks(self):
        # 2026-10-31 是周六，本周从 10-26 周一开始，且截到业务日期
        timex = self.check('这周', DAY(2026, 10, 26), DAY(2026, 10, 31), 'week')
        self.assertIn('尚未结束', timex.assumption)
        self.check('上周', DAY(2026, 10, 19), DAY(2026, 10, 25), 'week')
        self.check('上上周', DAY(2026, 10, 12), DAY(2026, 10, 18), 'week')

    def test_months(self):
        timex = self.check('本月', DAY(2026, 10, 1), DAY(2026, 10, 31), 'month')
        self.assertIn('尚未结束', timex.assumption)
        self.check('上个月', DAY(2026, 9, 1), DAY(2026, 9, 30), 'month')
        self.check('上月', DAY(2026, 9, 1), DAY(2026, 9, 30), 'month')
        self.check('上上个月', DAY(2026, 8, 1), DAY(2026, 8, 31), 'month')
        self.check('下个月', DAY(2026, 11, 1), DAY(2026, 11, 30), 'month')

    def test_quarters_and_years(self):
        self.check('本季度', DAY(2026, 10, 1), DAY(2026, 10, 31), 'quarter')
        self.check('上季度', DAY(2026, 7, 1), DAY(2026, 9, 30), 'quarter')
        self.check('今年', DAY(2026, 1, 1), DAY(2026, 10, 31), 'year')
        self.check('去年', DAY(2025, 1, 1), DAY(2025, 12, 31), 'year')
        self.check('前年', DAY(2024, 1, 1), DAY(2024, 12, 31), 'year')

    def test_year_boundary(self):
        today = DAY(2027, 1, 3)
        self.check('上个月', DAY(2026, 12, 1), DAY(2026, 12, 31), 'month', today)
        self.check('去年', DAY(2026, 1, 1), DAY(2026, 12, 31), 'year', today)
        self.check('本周', DAY(2026, 12, 28), DAY(2027, 1, 3), 'week', today)


class MonthAndDateTest(unittest.TestCase):
    def test_month_without_year_uses_nearest_past(self):
        self.assertEqual((first('8月', TODAY).start, first('8月', TODAY).end),
                         (DAY(2026, 8, 1), DAY(2026, 8, 31)))
        self.assertEqual(first('8月', TODAY).assumption, '')

    def test_future_month_falls_back_to_last_year_with_assumption(self):
        timex = first('12月', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2025, 12, 1), DAY(2025, 12, 31)))
        self.assertIn('未写年份', timex.assumption)

    def test_same_month_needs_no_fallback(self):
        timex = first('12月', DAY(2026, 12, 15))
        self.assertEqual((timex.start, timex.end), (DAY(2026, 12, 1), DAY(2026, 12, 31)))
        self.assertEqual(timex.assumption, '')

    def test_chinese_month_and_explicit_year(self):
        self.assertEqual(first('九月份', TODAY).start, DAY(2026, 9, 1))
        self.assertEqual(first('2026年8月', TODAY).start, DAY(2026, 8, 1))
        self.assertEqual(first('2025年12月', TODAY).start, DAY(2025, 12, 1))

    def test_day_level(self):
        self.assertEqual(first('8月15号', TODAY).start, DAY(2026, 8, 15))
        self.assertEqual(first('上个月15号', TODAY).start, DAY(2026, 9, 15))
        self.assertEqual(first('这个月15号', TODAY).start, DAY(2026, 10, 15))
        self.assertEqual(first('2026年9月30日', TODAY).start, DAY(2026, 9, 30))
        self.assertEqual(first('2026-08-01', TODAY).start, DAY(2026, 8, 1))

    def test_ranges(self):
        timex = first('8月1号到8月15号', TODAY)
        self.assertEqual((timex.start, timex.end, timex.bucket), (DAY(2026, 8, 1), DAY(2026, 8, 15), 'day'))
        timex = first('从9月到10月花了多少', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2026, 9, 1), DAY(2026, 10, 31)))
        timex = first('10月1日到10月7日', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2026, 10, 1), DAY(2026, 10, 7)))

    def test_afternoon_free_text_still_finds_date(self):
        self.assertEqual(first('我10月8号到10月15号在外面旅游，那几天花了多少', TODAY).start, DAY(2026, 10, 8))


class NearTest(unittest.TestCase):
    def test_near_days(self):
        timex = first('近7天', TODAY)
        self.assertEqual((timex.start, timex.end, timex.bucket), (DAY(2026, 10, 25), DAY(2026, 10, 31), 'day'))
        timex = first('最近30天', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2026, 10, 2), DAY(2026, 10, 31)))

    def test_near_months_and_years(self):
        timex = first('近3个月', TODAY)
        self.assertEqual((timex.start, timex.end, timex.bucket), (DAY(2026, 8, 1), DAY(2026, 10, 31), 'month'))
        timex = first('最近半年', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2026, 5, 1), DAY(2026, 10, 31)))
        timex = first('近两年', TODAY)
        self.assertEqual((timex.start, timex.end, timex.bucket), (DAY(2025, 1, 1), DAY(2026, 10, 31), 'year'))

    def test_near_does_not_swallow_vague_recent(self):
        self.assertEqual(find('最近钱不太够用', TODAY), [])
        self.assertEqual(find('前段时间花得有点多', TODAY), [])


class HolidayTest(unittest.TestCase):
    def test_fixed_holidays(self):
        timex = first('国庆出去玩花了多少', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2026, 10, 1), DAY(2026, 10, 7)))
        self.assertIn('调休', timex.assumption)
        timex = first('元旦', TODAY)
        self.assertEqual((timex.start, timex.end), (DAY(2026, 1, 1), DAY(2026, 1, 1)))

    def test_lunar_holidays_are_not_invented(self):
        for text in ['春节花了多少', '中秋出去玩', '端午', '清明', '五一人太多了']:
            with self.subTest(text=text):
                self.assertEqual(find(text, TODAY), [])
                self.assertTrue(unsupported(text, TODAY), text)


class UnsupportedTest(unittest.TestCase):
    def test_event_anchor_is_flagged_not_guessed(self):
        # 时间词本身仍可解析（这个月=2026-10），但"发了工资之后"这个锚点必须被标出来
        found = find('这个月发了工资之后花了多少', TODAY)
        self.assertEqual([item.start for item in found], [DAY(2026, 10, 1)])
        self.assertIn('发工资', unsupported('这个月发了工资之后花了多少', TODAY))

    def test_supported_phrase_is_not_flagged(self):
        self.assertEqual(unsupported('最近三个月花了多少', TODAY), [])
        self.assertIsNotNone(first('最近三个月花了多少', TODAY))

    def test_plain_question_has_no_time(self):
        self.assertEqual(find('我上个月是不是花超了？比再上个月呢', TODAY)[0].start, DAY(2026, 9, 1))
        self.assertEqual(find('钱都去哪了', TODAY), [])


class MultipleExpressionTest(unittest.TestCase):
    def test_two_expressions_in_order(self):
        found = find('上个月比上上个月多花了多少', TODAY)
        self.assertEqual([item.start for item in found], [DAY(2026, 9, 1), DAY(2026, 8, 1)])

    def test_longer_match_wins_over_overlap(self):
        found = find('上上个月', TODAY)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].start, DAY(2026, 8, 1))

    def test_business_date_is_required(self):
        with self.assertRaises(TypeError):
            find('上个月', None)


if __name__ == '__main__':
    unittest.main()
