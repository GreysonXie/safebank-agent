"""旁路比对（影子模式）：把模型的规划结果和确定性解析器对一遍，只写诊断，不改行为。

为什么需要这一层
----------------
时间与金额的换算现在由模型自己做。我们已经有了确定性解析器（``timex`` / ``money``），
但**不能直接接管**——那会改变线上行为。影子模式先只观察：每轮把"模型选的区间/金额"
和"解析器读到的东西"比对，用一行诊断记录结论（AGREED / PARTIAL / MISMATCH / UNPARSED），
积累一批数据后再决定哪一处值得硬约束、哪一处只需要提示候选。

硬约定
------
- 只写 stderr：不参与回答、不阻断、不改提示词、不额外调用模型；
- 任何异常都被吞掉，**绝不影响本轮对话**（解析器读不懂客户的话不是业务错误）；
- 日志里**没有客户原文、没有金额、没有日期**，只有结论代码与工具名，
  与既有"诊断只记录工具名、不记录客户文本或参数值"的约定一致。

实体（``entities``）暂不参与比对：规划阶段拿不到候选池（收款人/商户/代扣清单要到
工具回执里才有），没有候选就无法判断"模型选的那个对不对"，强行比对只会产生噪声。
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass

from . import money as money_parser
from . import timex as timex_parser

__all__ = ['Comparison', 'Report', 'inspect', 'emit', 'AGREED', 'PARTIAL', 'MISMATCH', 'UNPARSED', 'NONE', 'DIFF']

AGREED = 'AGREED'
PARTIAL = 'PARTIAL'
MISMATCH = 'MISMATCH'
UNPARSED = 'UNPARSED'
NONE = 'NONE'
DIFF = 'DIFF'

_MONEY_KEYS = ('minAmountMinor', 'maxAmountMinor')


@dataclass(frozen=True)
class Comparison:
    """一处比对结论。``detail`` 只给测试与本地调试看，**不进日志**。"""

    code: str = NONE
    detail: str = ''


@dataclass(frozen=True)
class Report:
    time: Comparison = Comparison()
    money: Comparison = Comparison()
    bucket: Comparison = Comparison()
    tools: tuple = ()
    checked: int = 0

    def line(self):
        """返回一行诊断（无客户数据）；本轮没有任何可比对参数时返回 None。"""
        if not self.checked:
            return None
        return 'NLU_SHADOW time=%s money=%s bucket=%s tools=%s' % (
            self.time.code, self.money.code, self.bucket.code, ','.join(self.tools) or '-')


def _tools(planned):
    if not isinstance(planned, dict):
        return []
    tools = planned.get('tools')
    if not isinstance(tools, list):
        return []
    return [item for item in tools if isinstance(item, dict)]


def _names(tools):
    names = []
    for item in tools:
        name = item.get('tool')
        if isinstance(name, str) and name:
            names.append(name[:40])
    return tuple(names)


def _args(item):
    value = item.get('args')
    return value if isinstance(value, dict) else {}


def _time_comparison(question, business_date, args):
    """返回 (结论, 命中的解析结果)。"""
    start, end = args.get('start'), args.get('end')
    if not isinstance(start, str) or not isinstance(end, str):
        return Comparison(), None
    try:
        intervals = timex_parser.find(question, business_date)
    except TypeError:
        # 业务日期缺失属于配置问题，不该记成"解析失败"污染统计，直接跳过
        return Comparison(), None
    if not intervals:
        return Comparison(UNPARSED, '解析器没读到时间说法'), None
    for item in intervals:
        if item.start.isoformat() == start and item.end.isoformat() == end:
            return Comparison(AGREED), item
    for item in intervals:
        if item.start.isoformat() <= end and start <= item.end.isoformat():
            return Comparison(PARTIAL), item
    return Comparison(MISMATCH), intervals[0]


def _bucket_comparison(time_code, matched, args):
    if time_code not in (AGREED, PARTIAL) or matched is None:
        return Comparison()
    bucket = args.get('bucket')
    if not isinstance(bucket, str) or not matched.bucket:
        return Comparison()
    if bucket == matched.bucket:
        return Comparison(AGREED)
    return Comparison(DIFF, '模型用 %s，解析建议 %s' % (bucket, matched.bucket))


def _money_targets(tools):
    """模型给出的金额：账单的金额阈值、转账金额。只取整数分。"""
    targets = []
    for item in tools:
        name = item.get('tool')
        args = _args(item)
        if name == 'statement_query':
            filters = args.get('filter')
            if isinstance(filters, dict):
                for key in _MONEY_KEYS:
                    value = filters.get(key)
                    if isinstance(value, int) and not isinstance(value, bool):
                        targets.append(value)
        elif name == 'transfer_preview':
            value = args.get('amountMinor')
            if isinstance(value, int) and not isinstance(value, bool):
                targets.append(value)
    return targets


def _money_comparison(question, tools):
    targets = _money_targets(tools)
    if not targets:
        return Comparison()
    amounts = money_parser.parse(question)
    if not amounts:
        return Comparison(UNPARSED, '客户话里没读到金额')
    minors = {item.minor for item in amounts}
    missing = [target for target in targets if target not in minors]
    if missing:
        return Comparison(MISMATCH, '%d 个金额与解析结果不符' % len(missing))
    return Comparison(AGREED)


def inspect(question, planned, business_date):
    """比对一轮规划；纯函数，不写日志、不抛业务异常。"""
    tools = _tools(planned)
    report_time, report_bucket, checked = Comparison(), Comparison(), 0
    for item in tools:
        if item.get('tool') != 'statement_query':
            continue
        args = _args(item)
        code, matched = _time_comparison(question, business_date, args)
        if code.code != NONE:
            checked += 1
            report_time = code
            report_bucket = _bucket_comparison(code.code, matched, args)
            break
    money_code = _money_comparison(question, tools)
    if money_code.code != NONE:
        checked += 1
    return Report(report_time, money_code, report_bucket, _names(tools), checked)


def emit(question, planned, business_date, stream=None):
    """写一行诊断；任何问题都静默返回 None，绝不影响本轮。

    设 ``NLU_SHADOW=off`` 可整层关掉（只是少一行日志，不改变任何业务行为）。
    """
    if str(os.environ.get('NLU_SHADOW', '')).strip().lower() in ('0', 'off', 'false', 'no'):
        return None
    try:
        line = inspect(question, planned, business_date).line()
    except Exception:  # noqa: BLE001 - 旁路诊断不允许影响主流程
        return None
    if line is None:
        return None
    try:
        (stream if stream is not None else sys.stderr).write(line + '\n')
    except Exception:  # noqa: BLE001
        return None
    return line
