"""金额解析的确定性测试：口语数字、简写读法、歧义标记与"不是钱"的排除。

语料来源分三类（与 nlu 语料集一致）：队友需求文档原话、历史真实会话、按真实场景联想的合理问句。
"""

import unittest

from safebank_agent.money import cn_number, first, parse


class CnNumberTest(unittest.TestCase):
    def test_basic_readings(self):
        self.assertEqual(cn_number('十二'), 12)
        self.assertEqual(cn_number('两百'), 200)
        self.assertEqual(cn_number('一百零五'), 105)
        self.assertEqual(cn_number('一万零五百'), 10500)

    def test_arabic_passthrough(self):
        self.assertEqual(cn_number('1500'), 1500)
        self.assertEqual(cn_number('1.5'), 1.5)

    def test_unknowable_returns_none(self):
        self.assertIsNone(cn_number('大概'))
        self.assertIsNone(cn_number(''))


class CurrencyUnitTest(unittest.TestCase):
    def test_yuan_variants(self):
        for text, minor in [
            ('五百块', 50000),
            ('500元', 50000),
            ('1500块', 150000),
            ('1.5万元', 1500000),  # 15000 元
            ('五万块', 5000000),
            ('转五万', 5000000),
        ]:
            with self.subTest(text=text):
                self.assertEqual(first(text).minor, minor)

    def test_thousands_separator(self):
        self.assertEqual(first('1,500元').minor, 150000)

    def test_full_width_digits_from_voice_transcription(self):
        self.assertEqual(first('转５００元').minor, 50000)


class ShorthandTest(unittest.TestCase):
    def test_trailing_digit_means_next_unit_down(self):
        for text, minor in [
            ('一千五', 150000),   # 1500 元
            ('3万5', 3500000),    # 35000 元
            ('一万五', 1500000),  # 15000 元
            ('两万三', 2300000),  # 23000 元
            ('一百五', 15000),    # 150 元（口语读法）
        ]:
            with self.subTest(text=text):
                self.assertEqual(first(text).minor, minor)

    def test_full_reading_is_not_shorthand(self):
        self.assertEqual(first('三千五百').minor, 350000)
        self.assertEqual(first('一百五十').minor, 15000)


class SubUnitTest(unittest.TestCase):
    def test_jiao_and_fen(self):
        self.assertEqual(first('五毛').minor, 50)
        self.assertEqual(first('三毛五').minor, 35)
        self.assertEqual(first('五块五').minor, 550)
        self.assertEqual(first('3块5').minor, 350)


class NotMoneyTest(unittest.TestCase):
    def test_dates_years_phones_and_counts_are_not_money(self):
        for text in ['2026', '2026年', '9月1号', '2026-10-31', '13800001234', '第3次', '30天', '25%']:
            with self.subTest(text=text):
                self.assertEqual(parse(text), [])

    def test_fractions_and_alphanumeric_ids_are_not_money(self):
        self.assertEqual(parse('每个月把退休金十分之一存起来'), [])
        self.assertEqual(parse('帮我把登录密码改成abc123'), [])
        self.assertEqual([item.minor for item in parse('R2和R3各买10万，一年期')], [10000000])

    def test_virtual_card_number_is_not_money(self):
        self.assertEqual(parse('卡号6222021234567890123'), [])

    def test_card_count_phrase_is_not_a_confirmed_amount(self):
        # 「5张100」有"张数×面额"和"100元"两种读法，不能静默算成 500 元
        self.assertNotIn(50000, [item.minor for item in parse('5张100')])


class AmbiguityTest(unittest.TestCase):
    def test_bare_number_is_flagged(self):
        amount = first('转200')
        self.assertEqual(amount.minor, 20000)
        self.assertTrue(amount.ambiguous)
        self.assertIn('单位', amount.reason)

    def test_explicit_unit_is_not_ambiguous(self):
        self.assertFalse(first('五百块').ambiguous)
        self.assertFalse(first('¥500').ambiguous)

    def test_multiple_amounts_are_all_returned(self):
        amounts = parse('转5000……算了500')
        self.assertEqual([item.minor for item in amounts], [500000, 50000])

    def test_single_statement_amount(self):
        amounts = parse('这月房租2400，我跟室友AA，把我那份转给房东')
        self.assertEqual(len(amounts), 1)
        self.assertEqual(amounts[0].minor, 240000)


if __name__ == '__main__':
    unittest.main()
