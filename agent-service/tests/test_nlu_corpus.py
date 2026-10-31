"""NLU 标尺：拿真实问句跑三个纯函数解析器，输出可量化的通过率。

标尺的意义
----------
之前"理解得好不好"只能靠感觉。这个模块把真实问句（队友需求文档原话、T020 真模型
跑测的问句、按场景联想的问句）连同**期望的时间区间 / 金额 / 实体**固定下来，
每次改动都能看到通过率，而不是靠印象。

现在覆盖的是解析层（timex / money / entities），不覆盖回答文风与工具选择；
真模型的端到端效果不在本模块内，属于下一阶段的"在线"评测。
"""

import json
import os
import unittest
from datetime import date

from safebank_agent.entities import Candidate, link
from safebank_agent.money import parse
from safebank_agent.timex import find, unsupported

CASES_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'nlu_cases.json')
SOURCES = {'doc', 'history', 'imagined'}
MIN_CASES = 80


def load():
    with open(CASES_PATH, encoding='utf-8') as handle:
        return json.load(handle)


def _time_rows(text, today):
    return [[item.start.isoformat(), item.end.isoformat(), item.bucket] for item in find(text, today)]


def _money_rows(text):
    return [item.minor for item in parse(text)]


def _candidate(key, candidates):
    return [Candidate(item['id'], item['name'], tuple(item.get('aliases') or ()), item.get('kind', ''))
            for item in candidates[key]]


class CorpusShapeTest(unittest.TestCase):
    """标尺本身的自检：条数、来源、字段完整性。"""

    def setUp(self):
        self.corpus = load()
        self.cases = self.corpus['cases']

    def test_business_date_is_explicit(self):
        self.assertEqual(date.fromisoformat(self.corpus['meta']['business_date']), date(2026, 10, 31))

    def test_enough_cases(self):
        self.assertGreaterEqual(len(self.cases), MIN_CASES, 'NLU 标尺至少要有 %d 条真实问句' % MIN_CASES)

    def test_ids_are_unique_and_sources_known(self):
        ids = [case['id'] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)), '编号重复')
        for case in self.cases:
            self.assertIn(case['source'], SOURCES, case['id'])
            self.assertTrue(case.get('utterance', '').strip(), case['id'])

    def test_every_case_checks_something(self):
        for case in self.cases:
            self.assertTrue({'time', 'money', 'unsupported', 'links'} & set(case), case['id'])

    def test_all_three_sources_present(self):
        used = {case['source'] for case in self.cases}
        self.assertEqual(used, SOURCES)

    def test_candidate_pools_are_consistent(self):
        pools = self.corpus['candidates']
        for key, pool in pools.items():
            ids = [item['id'] for item in pool]
            self.assertEqual(len(ids), len(set(ids)), key)
            for item in pool:
                self.assertTrue(item['name'].strip(), key)
        for case in self.cases:
            for item in case.get('links') or ():
                self.assertIn(item['of'], pools, case['id'])


class CorpusScoreTest(unittest.TestCase):
    """把标尺跑一遍并打印通过率；有任何一条不符就失败。"""

    def test_all_cases_match(self):
        corpus = load()
        today = date.fromisoformat(corpus['meta']['business_date'])
        stats = {'time': [0, 0], 'money': [0, 0], 'unsupported': [0, 0], 'links': [0, 0]}
        by_source = {}
        failures = []
        for case in corpus['cases']:
            text = case['utterance']
            group = case['group']
            by_source.setdefault(case['source'], 0)
            by_source[case['source']] += 1
            if 'time' in case:
                stats['time'][1] += 1
                expected = case['time']
                actual = _time_rows(text, today)
                if actual == expected:
                    stats['time'][0] += 1
                else:
                    failures.append('%s [%s] 时间：期望 %s，实际 %s' % (case['id'], group, expected, actual))
            if 'money' in case:
                stats['money'][1] += 1
                expected = case['money']
                actual = _money_rows(text)
                if actual == expected:
                    stats['money'][0] += 1
                else:
                    failures.append('%s [%s] 金额：期望 %s，实际 %s' % (case['id'], group, expected, actual))
            if 'unsupported' in case:
                stats['unsupported'][1] += 1
                expected = sorted(case['unsupported'])
                actual = sorted(unsupported(text, today))
                if actual == expected:
                    stats['unsupported'][0] += 1
                else:
                    failures.append('%s [%s] 不支持的表述：期望 %s，实际 %s' % (case['id'], group, expected, actual))
            for item in case.get('links') or ():
                stats['links'][1] += 1
                result = link(item['text'], _candidate(item['of'], corpus['candidates']))
                actual_best = result.best.id if result.best else None
                problems = [actual_best != item['expect_best']]
                if 'expect_ambiguous' in item:
                    problems.append(result.ambiguous != item['expect_ambiguous'])
                if any(problems):
                    failures.append('%s [%s] 实体「%s」：期望 best=%s ambiguous=%s，实际 best=%s ambiguous=%s（%s）'
                                    % (case['id'], group, item['text'], item['expect_best'],
                                       item.get('expect_ambiguous', '-'), actual_best, result.ambiguous, result.reason))
                else:
                    stats['links'][0] += 1
        print('\nNLU 标尺 %s：%d 条真实问句' % (today, sum(by_source.values())))
        for name, label in (('time', '时间'), ('money', '金额'), ('unsupported', '不支持项'), ('links', '实体')):
            passed, total = stats[name]
            if total:
                print('  %-6s %2d/%2d  %5.1f%%' % (label, passed, total, 100.0 * passed / total))
        print('  来源  ' + '  '.join('%s=%d' % (key, by_source[key]) for key in sorted(by_source)))
        if failures:
            print('  未通过 %d 条：' % len(failures))
            for line in failures:
                print('   - ' + line)
        self.assertEqual(failures, [], 'NLU 标尺有 %d 条不通过' % len(failures))


if __name__ == '__main__':
    unittest.main()
