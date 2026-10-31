"""中文口语金额解析：把「五百块」「1.5万」「3万5」换算成整数分。

纯函数，只依赖标准库：不查库、不调模型、不读环境变量。
当前状态：只被测试引用，**尚未接入对话主链路**（接入前先做旁路比对）。

口径
----
- 对外一律整数分（与账单、转账的 ``amount_minor`` 一致），四舍五入到分。
- 解析不确定时不改写客户的话：返回 ``ambiguous=True`` 并给出 ``reason``，
  由上层决定"回述确认"还是"追问"，本模块不替客户拍板。
- 只解析金额，不判断这笔钱是收入还是支出。

明确不支持的写法（返回空，交给上层追问，绝不猜）
-------------------------------------------------
- ``5张100`` 这类"张数×面额"的口语（存在张数与面额两种读法）。
- ``十块零五`` 这类"元+零+角/分"的方言读法（可能是 10.5 元，也可能是 10.05 元）。
- 带币种的口语（"美元/日元"）一律不在本模块范围内。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

__all__ = ["Amount", "cn_number", "parse", "first"]


_CN_DIGITS = {
    '零': 0, '〇': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4,
    '五': 5, '六': 6, '七': 7, '八': 8, '九': 9,
}
_CN_UNITS = {'十': 10, '百': 100, '千': 1000}
_CN_BIG = {'万': 10000, '亿': 100000000}

# 货币单位后面不该再出现这些字，否则说明命中的不是一笔钱（日期、次数、量词等）。
_NOT_MONEY_AFTER = r'(?![0-9零〇一二三四五六七八九十百千万亿年月日号岁个张次点周季度天晚夜小时分钟回堆些遍趟家杯份斤位整%])'
# 前面已经贴着数字、小数点或日期分隔符时不是一笔独立的钱。
_NOT_MONEY_BEFORE = r'(?<![\d.．/、-])'

# 不带"万/亿"的数字片段（避免贪婪吞掉大单位）。
_NUM_SMALL = r'(?:\d+(?:\.\d+)?|[零〇一二三四五六七八九十百千两]+)'
_NUM = r'(?:' + _NUM_SMALL + r'(?:万|亿)?)'

# "五百块"「5元」「1.5万元」「五块五」
_CURRENCY = re.compile(
    r'(?P<main>' + _NUM_SMALL + r')(?P<big>万|亿)?'
    r'(?P<cur>元|块钱|块|圆|人民币)'
    r'(?:(?P<sub>' + _NUM_SMALL + r')(?P<subunit>分|角|毛)|(?P<tenth>[0-9一二三四五六七八九两]))?'
)
# "五毛"「三毛五」「三毛五分」的角/分单独成句
_SMALL_CURRENCY = re.compile(
    r'(?P<main>' + _NUM_SMALL + r')(?P<cur>角|毛|分)'
    r'(?P<sub>[0-9一二三四五六七八九两])?(?:分)?'
    r'(?![\d零〇一二三四五六七八九十百千万亿元块毛角钱之])'
)
# "3万5"「一千五」「一百五」：单位后面跟一个孤零零的数字，按"下一级单位"读。
# 前后都要求不是数字字，保证整段数字正好是"X单位Y"，不会把「一千五百五」切成两段。
_SHORTHAND = re.compile(
    r'(?<![0-9零〇一二三四五六七八九十百千万亿两])'
    r'(?P<main>' + _NUM_SMALL + r')(?P<big>万|亿|千|百)'
    r'(?P<tenth>[0-9一二三四五六七八九两])' + _NOT_MONEY_AFTER
)
# "转5万"「1.5万」：只有大单位，没有货币词
_BARE_BIG = re.compile(
    r'(?<![\d.．])(?P<main>' + _NUM_SMALL + r')(?P<big>万|亿)' + _NOT_MONEY_AFTER
)
# "¥500"「￥1500」：符号在前，单位已知，不算歧义
_SYMBOL = re.compile(r'[¥￥]\s*(?P<main>' + _NUM_SMALL + r')(?P<big>万|亿)?')
# "转200"「1500」：连单位都没有，按元处理但标记歧义。
# 排除紧跟在字母（abc123）、"."、"分/之"（十分之一）后面的数字：那些不是一笔钱。
_BARE = re.compile(r'(?<![\d.．¥￥分之A-Za-z])' + _NUM_SMALL + _NOT_MONEY_AFTER)

_PHONE_LIKE = re.compile(r'\d{11,}')
# 只有"汉字数字"的读数：没有十百千万亿这类单位时不当金额（一下、一共、乱七八糟、七八）
_CN_UNIT_CHARS = re.compile(r'[十百千万亿]')
_CN_ONLY = re.compile(r'[零〇一二三四五六七八九十百千万亿两]+')
# 购物节名字与排名里的数字不是金额（双十一、双11、前十、前5名）
_NOT_MONEY_WORDS = re.compile(
    r'双十一|双11|双十二|双12|十一假期|十一长假|十一期间'
    r'|(?:前|第|top|TOP|Top)\s*[一二三四五六七八九十\d]+'
)
_SHORTHAND_UNITS = {**_CN_BIG, **_CN_UNITS}
# 结尾只剩单个数字的口语读法（「一千五百五」可能是 1550 也可能是 1505），不静默取一种
_TRAILING_BARE = re.compile(r'[万千百]([0-9零〇一二三四五六七八九两])$')
# 日期片段整体跳过：2026-10-31、10/31、2026年10月31日
_DATE_SPAN = re.compile(
    r'\d{1,4}\s*(?:[-/]|年)\s*\d{1,2}\s*(?:[-/]|月)\s*\d{1,2}\s*[日号]?'
    r'|\d{4}\s*-\s*\d{1,2}(?!\d)'
    r'|\d{1,2}\s*/\s*\d{1,2}(?!\d)'
)
_FULLWIDTH = str.maketrans('０１２３４５６７８９', '0123456789')


@dataclass(frozen=True)
class Amount:
    """一处金额。``text`` 是原话片段，便于上层回述"您说的 500 元"。"""

    text: str
    minor: int
    ambiguous: bool = False
    reason: str = ''


def cn_number(text):
    """把数字片段读成 Decimal；读不出来返回 None。

    这里用的是"严格读法"：``一千五`` = 1005，不是 1500。
    口语简写（1500）由 :func:`parse` 里的 ``_SHORTHAND`` 单独处理，
    避免把两种读法混在一个函数里。
    """
    if not isinstance(text, str):
        return None
    s = text.strip().replace(' ', '').replace('．', '.').replace('，', '').replace(',', '')
    s = s.translate(_FULLWIDTH)
    if not s:
        return None
    if re.fullmatch(r'\d+(?:\.\d+)?', s):
        return Decimal(s)
    total = Decimal(0)
    section = Decimal(0)
    number = Decimal(0)
    for ch in s:
        if ch in _CN_DIGITS:
            number = Decimal(_CN_DIGITS[ch])
        elif ch in _CN_UNITS:
            unit = _CN_UNITS[ch]
            if number == 0:
                number = Decimal(1)  # 「十二」的十
            section += number * unit
            number = Decimal(0)
        elif ch in _CN_BIG:
            unit = _CN_BIG[ch]
            section += number
            if section == 0:
                section = Decimal(1)  # 「万」单独出现
            total += section * unit
            section = Decimal(0)
            number = Decimal(0)
        else:
            return None
    return total + section + number


def _yuan(main, big=None):
    value = cn_number(main)
    if value is None:
        return None
    if big and big in _CN_BIG:
        value *= _CN_BIG[big]
    return value


def _minor(yuan):
    return int((Decimal(yuan) * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def _from_currency(match):
    text = match.group(0)
    yuan = _yuan(match.group('main'), match.group('big'))
    if yuan is None:
        return Amount(text, 0, True, '金额读数失败，需要客户重述')
    subunit = match.group('subunit')
    if subunit and match.group('sub'):
        part = cn_number(match.group('sub'))
        if part is None:
            return Amount(text, 0, True, '角/分读数失败，需要客户重述')
        yuan += part / (100 if subunit == '分' else 10)
    else:
        tenth = match.group('tenth')
        if tenth:
            value = cn_number(tenth)
            if value is None:
                return Amount(text, 0, True, '角/分读数失败，需要客户重述')
            yuan += value / 10
    return Amount(text, _minor(yuan))


def _from_small_currency(match):
    text = match.group(0)
    value = cn_number(match.group('main'))
    if value is None:
        return Amount(text, 0, True, '角/分读数失败，需要客户重述')
    unit = match.group('cur')
    if unit == '分':
        yuan = value / 100
    else:
        yuan = value / 10
        sub = match.group('sub')
        if sub:
            part = cn_number(sub)
            if part is None:
                return Amount(text, 0, True, '角/分读数失败，需要客户重述')
            yuan += part / 100
    return Amount(text, _minor(yuan))


def _from_shorthand(match):
    text = match.group(0)
    unit = _SHORTHAND_UNITS.get(match.group('big'))
    lead = cn_number(match.group('main'))
    tenth = cn_number(match.group('tenth'))
    if unit is None or lead is None or tenth is None:
        return Amount(text, 0, True, '金额读数失败，需要客户重述')
    # 「3万5」= 3.5 万；「一千五」= 1.5 千；「一百五」= 1.5 百
    return Amount(text, _minor(lead * unit + tenth * unit / 10))


def _from_big(match):
    text = match.group(0)
    yuan = _yuan(match.group('main'), match.group('big'))
    if yuan is None:
        return Amount(text, 0, True, '金额读数失败，需要客户重述')
    return Amount(text, _minor(yuan))


def _from_bare(text):
    if _PHONE_LIKE.fullmatch(text):
        return None  # 手机号不是金额
    if _CN_ONLY.fullmatch(text) and not _CN_UNIT_CHARS.search(text):
        # 「一下」「一共」「一起」「乱七八糟」里的汉字数字不是钱；
        # 「五百」「五十」「一千」这类带单位的才算。
        return None
    yuan = cn_number(text)
    if yuan is None or yuan <= 0:
        return None
    if _TRAILING_BARE.search(text):
        return Amount(text, _minor(yuan), True,
                      '口语读法有歧义（如「一千五百五」可能指 1550 或 1505），需要回述确认')
    return Amount(text, _minor(yuan), True, '未说明货币单位，按元处理')


def _from_symbol(match):
    yuan = _yuan(match.group('main'), match.group('big'))
    if yuan is None:
        return Amount(match.group(0), 0, True, '金额读数失败，需要客户重述')
    return Amount(match.group(0), _minor(yuan))


def parse(text):
    """返回文本里所有金额，按出现顺序；相邻重叠的只保留一个（更具体的优先）。"""
    if not isinstance(text, str) or not text.strip():
        return []
    source = text.replace('，', ',').replace('．', '.').translate(_FULLWIDTH)
    # "1,500" 这种千分位先并成一个数
    source = re.sub(r'(?<=\d),(?=\d{3}(?!\d))', '', source)
    found = []
    taken = [match.span() for match in _DATE_SPAN.finditer(source)]
    taken += [match.span() for match in _NOT_MONEY_WORDS.finditer(source)]

    def take(pattern, factory):
        for match in pattern.finditer(source):
            span = match.span()
            if any(span[0] < end and start < span[1] for start, end in taken):
                continue
            amount = factory(match) if callable(factory) else factory
            if amount is None:
                continue
            taken.append(span)
            found.append((span[0], amount))

    take(_CURRENCY, _from_currency)
    take(_SMALL_CURRENCY, _from_small_currency)
    take(_SHORTHAND, _from_shorthand)
    take(_BARE_BIG, _from_big)
    take(_SYMBOL, _from_symbol)
    take(_BARE, lambda match: _from_bare(match.group(0)))
    found.sort(key=lambda item: item[0])
    return [amount for _, amount in found]


def first(text):
    """第一处金额；没有则 None。多处金额时上层应回述确认，不要静默取一个。"""
    amounts = parse(text)
    return amounts[0] if amounts else None
