"""实体链接：把客户口语里的指代（"那个视频会员""我那张储蓄卡""老张"）对到真实候选上。

纯函数，只依赖标准库：候选由调用方传入（账户、商户、收款人、代扣授权），
本模块不查库、不调模型，**也不替客户认人**——匹配不唯一时只给候选并标记歧义。

当前状态：只被测试引用，**尚未接入对话主链路**。

口径
----
- 名字里的 ``〔…〕`` 是安全隔离标记，``（合成）`` 是演示标注，匹配前统一去掉，
  与 ``models.py`` 离线模型的既有做法保持一致。
- 口语指代先剥掉"那个/这个/上次/刚才"这类指示词再匹配。
- 只在**唯一且够像**时才返回 ``best``；重名、只在开头两字相同、模糊相似度不足，
  一律返回候选＋``ambiguous``，由上层请客户指定。禁止默认取第一个。
"""

from __future__ import annotations

import difflib
import re
import unicodedata
from dataclasses import dataclass, field

__all__ = ['Candidate', 'Match', 'Link', 'normalize', 'link', 'best']

_ACCEPT = 0.55          # 低于这个分数等于没匹配上；0.55 恰好是"只共享开头两字"，只用来提示候选
_CONFIDENT = 0.75       # 只有这个分数以上、且不歧义时才敢直接认
_AMBIGUOUS_GAP = 0.10   # 前两名差得这么近就必须问客户

_DEMONSTRATIVES = ('那个', '这个', '那次', '这次', '上次', '刚才', '之前', '那张', '这张',
                   '那笔', '这笔', '那条', '这条', '我的', '我', '刚才那个', '刚才的')
_TRAILING = ('的', '那个', '这个')
_PUNCT = re.compile(r'[\s,，。.;；:：!！?？、\'"“”‘’()（）\[\]【】<>《》/\\|_+=*&^%$#@~`-]+')


@dataclass(frozen=True)
class Candidate:
    """一个可被指代的对象。``kind`` 只用于报告，不参与匹配。"""

    id: str
    name: str
    aliases: tuple = ()
    kind: str = ''

    def texts(self):
        return (self.name,) + tuple(self.aliases)


@dataclass(frozen=True)
class Match:
    candidate: Candidate
    score: float
    how: str


@dataclass(frozen=True)
class Link:
    query: str
    matches: tuple = ()
    ambiguous: bool = False
    reason: str = ''
    suggestions: tuple = field(default=())

    @property
    def best(self):
        """唯一且够像时返回候选，否则 None（调用方应请客户确认）。"""
        if self.ambiguous or not self.matches:
            return None
        return self.matches[0].candidate


def normalize(text):
    """去掉隔离标记、演示标注、标点与大小写差异，得到可比较的名字。"""
    if not isinstance(text, str):
        return ''
    value = unicodedata.normalize('NFKC', text)
    value = value.replace('〔', '').replace('〕', '').replace('【', '').replace('】', '')
    value = re.sub(r'[（(]\s*合成\s*[）)]', '', value)
    value = value.replace('演示', '')
    value = _PUNCT.sub('', value)
    return value.casefold().strip()


def _strip_demonstratives(query):
    value = query
    changed = True
    while changed:
        changed = False
        for word in _DEMONSTRATIVES:
            if value.startswith(word) and len(value) > len(word):
                value = value[len(word):]
                changed = True
                break
    for word in _TRAILING:
        if value.endswith(word) and len(value) > len(word):
            value = value[: -len(word)]
            break
    return value


def _bigrams(text):
    if len(text) < 2:
        return {text} if text else set()
    return {text[index:index + 2] for index in range(len(text) - 1)}


def _shared_prefix(left, right):
    limit = min(len(left), len(right))
    index = 0
    while index < limit and left[index] == right[index]:
        index += 1
    return index


def _score(query, text):
    """返回 (分数, 依据)。分数是"客户说的这段和这个名字有多像"。"""
    if not query or not text:
        return 0.0, ''
    if query == text:
        return 1.0, 'exact'
    if query.isdigit() and query in text:
        return 1.0, 'number'
    if text.startswith(query):
        return 0.85, 'prefix'
    if query in text:
        return 0.75, 'contains'
    if text in query:
        return 0.80, 'embedded'
    overlap = _bigrams(query) & _bigrams(text)
    if overlap:
        jaccard = len(overlap) / len(_bigrams(query) | _bigrams(text))
        if jaccard >= 0.5:
            return 0.60 + 0.20 * jaccard, 'overlap'
    ratio = difflib.SequenceMatcher(None, query, text).ratio()
    if ratio >= 0.6:
        return 0.60 * ratio + 0.20, 'fuzzy'
    prefix = _shared_prefix(query, text)
    if prefix >= 2:
        return 0.55, 'shared-prefix'
    return 0.0, ''


def link(query, candidates):
    """把 ``query`` 对到候选列表上。返回带歧义标记的候选集合，不替客户做决定。"""
    raw = query if isinstance(query, str) else ''
    stripped = normalize(_strip_demonstratives(normalize(raw)))
    if not stripped:
        return Link(raw, reason='没有可匹配的指代')
    matches = []
    for candidate in candidates or ():
        best_score, best_how = 0.0, ''
        for text in candidate.texts():
            score, how = _score(stripped, normalize(text))
            if score > best_score:
                best_score, best_how = score, how
        if best_score >= _ACCEPT:
            matches.append(Match(candidate, round(best_score, 4), best_how))
    matches.sort(key=lambda item: (-item.score, item.candidate.id))
    if not matches:
        return Link(raw, reason='没有找到匹配的候选，需要客户补全名称')
    top = matches[0]
    rivals = [item for item in matches[1:] if top.score - item.score <= _AMBIGUOUS_GAP]
    if rivals:
        return Link(raw, tuple(matches[:5]), True,
                    '有 %d 个同样接近的候选，需要客户指定是哪一个' % (len(rivals) + 1))
    if top.score < _CONFIDENT:
        return Link(raw, tuple(matches[:5]), True,
                    '只有相似度较低的候选（%s），需要客户确认' % top.candidate.name)
    if top.how == 'shared-prefix':
        return Link(raw, tuple(matches[:5]), True,
                    '只在开头两字相同，不能确认就是 %s，需要客户确认' % top.candidate.name)
    return Link(raw, tuple(matches[:5]), False, '唯一匹配：%s' % top.candidate.name)


def best(query, candidates):
    """唯一且够像时返回候选；否则 None（调用方必须请客户确认，不能取第一个）。"""
    return link(query, candidates).best
