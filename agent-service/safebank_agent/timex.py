"""中文时间表达解析：把客户口语里的时间说法换算成账单查询需要的明确区间。

纯函数，只依赖标准库与 :mod:`safebank_agent.money` 的数字读法。
当前状态：只被测试引用，**尚未接入对话主链路**（接入前先做旁路比对）。

口径
----
- 区间左闭右闭（``YYYY-MM-DD``），与 ``statement_query`` 的 start/end 一致。
- 相对说法一律以「业务日期」为基准，本模块**不读系统时钟**；业务日期由调用方传入。
- 「进行中」的期间（本周/本月/本季度/今年）截到业务日期，并在 ``assumption`` 里写明，
  与账单年报「本年进行中」的既有口径保持一致。
- 缺少年份时按「最近一个已过去的同月」推断，并写进 ``assumption`` 供上层回述，不静默换年份。
- 读不出来就返回空，由上层追问；本模块不猜。

明确不支持（:func:`unsupported` 能查出来 → 上层应澄清，不编造日期）
-------------------------------------------------------------------
- 农历节日（春节/中秋/端午/清明）与放假天数不固定的五一小长假：需要逐年放假安排表。
- 事件锚点（"发了工资之后"）与含糊说法（"最近""前段时间""账单周期"）。
- 说到这些词时本模块返回空，绝不自行给一个看似合理的区间。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta

from .money import cn_number

__all__ = ['Timex', 'find', 'first', 'unsupported']

_NUM = r'(?:\d{1,2}|[零〇一二三四五六七八九十两]{1,3})'
_CONNECTOR = r'(?:到|至|~|～|—|－|-|一直到)'

_DAY_WORDS = [('大前天', 3), ('前天', 2), ('前日', 2), ('昨天', 1), ('昨日', 1), ('今天', 0), ('今日', 0)]
_WEEK_WORDS = [('上上个星期', -2), ('上上个礼拜', -2), ('上上星期', -2), ('上上礼拜', -2), ('上上', -2),
               ('上个星期', -1), ('上个礼拜', -1), ('上星期', -1), ('上礼拜', -1), ('上周', -1),
               ('这个星期', 0), ('这个礼拜', 0), ('本星期', 0), ('这星期', 0), ('这礼拜', 0), ('本周', 0), ('这周', 0)]
_MONTH_WORDS = [('上上个月', -2), ('上上月', -2), ('上个月', -1), ('上月', -1), ('前一个月', -1), ('前一月', -1),
                ('这个月', 0), ('这月', 0), ('本月', 0), ('当月', 0),
                ('下个月', 1), ('下月', 1), ('下一个月', 1)]
_QUARTER_WORDS = [('上个季度', -1), ('上季度', -1), ('这个季度', 0), ('本季度', 0), ('这季度', 0)]
_YEAR_WORDS = [('前年', -2), ('去年', -1), ('上年', -1), ('上一年', -1), ('今年', 0), ('本年', 0), ('明年', 1)]

_UNSUPPORTED_PATTERNS = (
    # (匹配式, 报告给上层的名字)：说到这些词时必须澄清，不能自行给一个区间
    (re.compile(r'春节|过年|除夕'), '春节'),
    (re.compile(r'中秋'), '中秋'),
    (re.compile(r'端午'), '端午'),
    (re.compile(r'清明'), '清明'),
    (re.compile(r'五一|劳动节'), '五一'),
    (re.compile(r'双十一|双11'), '双十一'),
    (re.compile(r'情人节'), '情人节'),
    (re.compile(r'暑假'), '暑假'),
    (re.compile(r'寒假'), '寒假'),
    (re.compile(r'开学'), '开学'),
    (re.compile(r'发(?:了|放)?工资|工资(?:到账|到手|发放)|发薪'), '发工资'),
    (re.compile(r'最近'), '最近'),
    (re.compile(r'前段时间|上段时间|这阵子'), '前段时间'),
    (re.compile(r'账单周期'), '账单周期'),
)

_FIXED_HOLIDAYS = [
    # 只有日期固定的节日才能在没有逐年放假安排表时安全换算
    (re.compile(r'国庆节?|十一假期|十一长假'), (10, 1), (10, 7), '国庆'),
    (re.compile(r'元旦|新年'), (1, 1), (1, 1), '元旦'),
]

_RANGE_MD = re.compile(
    r'(?:(?P<y1>\d{4})\s*年\s*)?(?P<m1>' + _NUM + r')\s*月\s*(?P<d1>' + _NUM + r')\s*[日号]\s*'
    + _CONNECTOR + r'\s*'
    r'(?:(?P<y2>\d{4})\s*年\s*)?(?P<m2>' + _NUM + r')\s*月\s*(?P<d2>' + _NUM + r')\s*[日号]'
)
_RANGE_M = re.compile(
    r'(?:(?P<y1>\d{4})\s*年\s*)?(?P<m1>' + _NUM + r')\s*月份?\s*' + _CONNECTOR + r'\s*'
    r'(?:(?P<y2>\d{4})\s*年\s*)?(?P<m2>' + _NUM + r')\s*月份?'
)
_OFFSET_DAY = re.compile(
    r'(?P<which>上上|上个|上|这|本|下)\s*个?\s*月\s*(?P<day>' + _NUM + r')\s*[日号]'
)
_MONTH_DAY = re.compile(r'(?:(?P<y>\d{4})\s*年\s*)?(?P<m>' + _NUM + r')\s*月\s*(?P<d>' + _NUM + r')\s*[日号]')
_ISO_DATE = re.compile(r'(?P<y>\d{4})-(?P<m>\d{1,2})-(?P<d>\d{1,2})')
_MONTH = re.compile(r'(?:(?P<y>\d{4})\s*年\s*)?(?P<m>' + _NUM + r')\s*月份?')
_YEAR = re.compile(r'(?P<y>\d{4})\s*年(?!\s*(?:' + _NUM + r')\s*月)')
_NEAR = re.compile(
    r'(?:近|最近|过去)\s*(?P<n>' + _NUM + r'|半|一)\s*个?\s*(?P<unit>天|日|周|星期|礼拜|月|年)'
)


@dataclass(frozen=True)
class Timex:
    """一处时间表达。``text`` 是原话片段，``assumption`` 是需要向客户回述的假设。"""

    text: str
    start: date
    end: date
    bucket: str
    label: str
    assumption: str = ''


def _int(value):
    if value is None:
        return None
    number = cn_number(value)
    if number is None or number != int(number):
        return None
    return int(number)


def _month_start(day, offset=0):
    total = day.year * 12 + (day.month - 1) + offset
    return date(total // 12, total % 12 + 1, 1)


def _month_end(day, offset=0):
    return _month_start(day, offset + 1) - timedelta(days=1)


def _week_start(day):
    return day - timedelta(days=day.weekday())


def _quarter_start(day, offset=0):
    index = (day.month - 1) // 3 + offset
    return date(day.year + index // 4, index % 4 * 3 + 1, 1)


def _quarter_end(day, offset=0):
    return _quarter_start(day, offset + 1) - timedelta(days=1)


def _year_for_month(month, today):
    """没写年份的「8月」按最近一个已过去的 8 月算，并说明理由。"""
    if month <= today.month:
        return today.year, ''
    return today.year - 1, '未写年份，按最近一个已过去的 %d 月（%d 年）理解' % (month, today.year - 1)


def _candidates(text, today):
    """所有候选。返回 (起点, 终点, Timex)；挑选交给 :func:`find`。"""
    found = []
    if not isinstance(text, str):
        return found

    def add(match, timex):
        if timex is not None and timex.end >= timex.start:
            found.append((match.start(), match.end(), timex))

    for match in _RANGE_MD.finditer(text):
        y1, y2 = _int(match.group('y1')), _int(match.group('y2'))
        m1, d1 = _int(match.group('m1')), _int(match.group('d1'))
        m2, d2 = _int(match.group('m2')), _int(match.group('d2'))
        if None in (m1, d1, m2, d2) or not (1 <= m1 <= 12 and 1 <= m2 <= 12):
            continue
        try:
            first_year = y1 or _year_for_month(m1, today)[0]
            second_year = y2 or first_year
            start, end = date(first_year, m1, d1), date(second_year, m2, d2)
        except ValueError:
            continue
        if start > end:
            start, end = end, start
        add(match, Timex(match.group(0), start, end, 'day', '日期区间'))

    for match in _RANGE_M.finditer(text):
        m1, m2 = _int(match.group('m1')), _int(match.group('m2'))
        if None in (m1, m2) or not (1 <= m1 <= 12 and 1 <= m2 <= 12):
            continue
        y1, y2 = _int(match.group('y1')), _int(match.group('y2'))
        base = y1 or _year_for_month(m2, today)[0]
        start = date(base, m1, 1)
        end = _month_end(date(y2 or base, m2, 1))
        if start > end:
            start, end = end, start
        add(match, Timex(match.group(0), start, end, 'month', '月份区间'))

    for match in _OFFSET_DAY.finditer(text):
        day = _int(match.group('day'))
        offset = {'上上': -2, '上个': -1, '上': -1, '这': 0, '本': 0, '下': 1}[match.group('which')]
        if day is None or not 1 <= day <= 31:
            continue
        try:
            value = date(_month_start(today, offset).year, _month_start(today, offset).month, day)
        except ValueError:
            continue
        add(match, Timex(match.group(0), value, value, 'day', '指定日'))

    for match in _MONTH_DAY.finditer(text):
        m, d = _int(match.group('m')), _int(match.group('d'))
        y = _int(match.group('y'))
        if None in (m, d) or not 1 <= m <= 12:
            continue
        note = ''
        if y is None:
            y, note = _year_for_month(m, today)
        try:
            value = date(y, m, d)
        except ValueError:
            continue
        add(match, Timex(match.group(0), value, value, 'day', '指定日', note))

    for match in _ISO_DATE.finditer(text):
        try:
            value = date(int(match.group('y')), int(match.group('m')), int(match.group('d')))
        except ValueError:
            continue
        add(match, Timex(match.group(0), value, value, 'day', '指定日'))

    for match in _MONTH.finditer(text):
        m = _int(match.group('m'))
        if m is None or not 1 <= m <= 12:
            continue
        y = _int(match.group('y'))
        year, note = (y, '') if y else _year_for_month(m, today)
        start = date(year, m, 1)
        add(match, Timex(match.group(0), start, _month_end(start), 'month', '%d年%d月' % (year, m), note))

    for match in _YEAR.finditer(text):
        year = int(match.group('y'))
        add(match, Timex(match.group(0), date(year, 1, 1), date(year, 12, 31), 'year', '%d年' % year))

    for pattern, (sm, sd), (em, ed), label in _FIXED_HOLIDAYS:
        for match in pattern.finditer(text):
            start = date(today.year, sm, sd)
            if start > today:
                start = date(today.year - 1, sm, sd)
            end = date(start.year, em, ed)
            add(match, Timex(match.group(0), start, end, 'day', label,
                             '“%s”按日期固定为 %s 至 %s 推算，未考虑调休与个人休假' % (label, start, end)))

    for word, offset in _DAY_WORDS:
        for match in re.finditer(word, text):
            value = today - timedelta(days=offset)
            add(match, Timex(match.group(0), value, value, 'day', word))

    for word, offset in _WEEK_WORDS:
        for match in re.finditer(word, text):
            start = _week_start(today) + timedelta(days=7 * offset)
            end = start + timedelta(days=6)
            note = ''
            if offset == 0:
                end = today
                note = '本周尚未结束，区间截至业务日期 %s' % today
            add(match, Timex(match.group(0), start, end, 'week', word, note))

    for word, offset in _MONTH_WORDS:
        for match in re.finditer(word, text):
            start, end = _month_start(today, offset), _month_end(today, offset)
            note = ''
            if offset == 0:
                end = today
                note = '本月尚未结束，区间截至业务日期 %s' % today
            add(match, Timex(match.group(0), start, end, 'month', word, note))

    for word, offset in _QUARTER_WORDS:
        for match in re.finditer(word, text):
            start, end = _quarter_start(today, offset), _quarter_end(today, offset)
            note = ''
            if offset == 0:
                end = today
                note = '本季度尚未结束，区间截至业务日期 %s' % today
            add(match, Timex(match.group(0), start, end, 'quarter', word, note))

    for word, offset in _YEAR_WORDS:
        for match in re.finditer(word, text):
            start = date(today.year + offset, 1, 1)
            end = date(today.year + offset, 12, 31)
            note = ''
            if offset == 0:
                end = today
                note = '今年尚未结束，区间截至业务日期 %s' % today
            add(match, Timex(match.group(0), start, end, 'year', word, note))

    for match in _NEAR.finditer(text):
        add(match, _near(match, today))

    return found


def _near(match, today):
    raw = match.group('n')
    unit = match.group('unit')
    if raw == '半':
        # 「近半年」按 6 个自然月，「近半个月」按 15 天；不把"半年"读成"半年份"
        if unit == '年':
            start, end, bucket = _month_start(today, -5), today, 'month'
        else:
            start, end, bucket = today - timedelta(days=14), today, 'day'
        return Timex(match.group(0), start, end, bucket, match.group(0),
                     '“%s”按业务日期 %s 换算为 %s 至 %s' % (match.group(0), today, start, end))
    count = 1 if raw == '一' else _int(raw)
    if count is None or not 1 <= count <= 60:
        return None
    if unit in ('天', '日'):
        start, end, bucket = today - timedelta(days=count - 1), today, 'day'
    elif unit in ('周', '星期', '礼拜'):
        start, end, bucket = today - timedelta(days=count * 7 - 1), today, 'week'
    elif unit == '月':
        start, end, bucket = _month_start(today, -(count - 1)), today, 'month'
    else:
        start, end, bucket = date(today.year - count + 1, 1, 1), today, 'year'
    return Timex(match.group(0), start, end, bucket, match.group(0),
                 '“%s”按业务日期 %s 换算为 %s 至 %s' % (match.group(0), today, start, end))


def _chosen(text, today):
    if not isinstance(today, date):
        raise TypeError('today 必须是 datetime.date（业务日期），不能省略')
    candidates = _candidates(text, today)
    candidates.sort(key=lambda item: (item[0], -(item[1] - item[0])))
    chosen = []
    last_end = -1
    for start, end, timex in candidates:
        if start < last_end:
            continue
        chosen.append((start, end, timex))
        last_end = end
    return chosen


def find(text, today):
    """返回文本里所有时间表达，按出现顺序；重叠时保留更长/更具体的一个。"""
    return [timex for _, _, timex in _chosen(text, today)]


def first(text, today):
    """第一处时间表达；没有则 None。"""
    found = find(text, today)
    return found[0] if found else None


def unsupported(text, today):
    """返回文本里出现、但**没有被任何已识别区间覆盖**的不支持说法（上层应据此澄清）。"""
    if not isinstance(text, str):
        return []
    spans = [(start, end) for start, end, _ in _chosen(text, today)]
    result = []
    for pattern, label in _UNSUPPORTED_PATTERNS:
        for match in pattern.finditer(text):
            index, stop = match.start(), match.end()
            if not any(start <= index and stop <= end for start, end in spans):
                result.append(label)
                break
    return result
