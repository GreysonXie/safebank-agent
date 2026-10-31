"""B2/B5：受控结束（abandon）与陈旧检查点重跑（rethread）的安全边界。

只验证状态机与调用边界，不接触真实 MySQL、不发起银行调用。
"""
import unittest
from contextlib import contextmanager

from safebank_agent.contracts import ProtocolError
from safebank_agent.runtime import Runtime
from safebank_agent.store import Store


class FakeCursor:
    """按 SQL 关键字响应；同时记录执行过的语句顺序。"""

    def __init__(self, runs):
        self.runs = list(runs)
        self.log = []
        self.rowcount = 1
        self._next = None

    def execute(self, sql, params=None):
        normalized = ' '.join(sql.split())
        self.log.append((normalized, params))
        self.rowcount = 1
        if 'MAX(sequence)' in normalized:
            self._next = {'n': 1}
        elif normalized.startswith('SELECT * FROM agent_runs'):
            self._next = self.runs.pop(0) if len(self.runs) > 1 else self.runs[0]
        elif normalized.startswith('SELECT * FROM agent_thread_bindings'):
            self._next = self.runs.pop(0) if self.runs else None
        else:
            self._next = None

    def fetchone(self):
        return self._next


class FakeStore(Store):
    def __init__(self, runs):
        self.rows = runs
        self.cursor = None

    @contextmanager
    def transaction(self):
        self.cursor = FakeCursor(self.rows)
        yield self.cursor

    def executed(self, fragment):
        return [entry for entry in self.cursor.log if fragment in entry[0]]


def run_row(state, result=None, turn='turn-1'):
    return {'id': turn, 'state': state, 'result_json': result, 'java_turn_id': turn,
            'output_version': 2 if result else None, 'binding_id': 'binding-1'}


class AbandonTest(unittest.TestCase):
    def test_pending_review_run_is_closed_and_keeps_its_request(self):
        store = FakeStore([run_row('RECONCILIATION_REQUIRED'), run_row('INTERRUPTED')])
        closed = store.abandon('turn-1')
        self.assertEqual('INTERRUPTED', closed['state'])
        updates = store.executed("state='INTERRUPTED'")
        self.assertEqual(1, len(updates), '只允许一条受保护终态更新')
        self.assertIn("state='RECONCILIATION_REQUIRED'", updates[0][0], '更新必须带状态条件，防止写回')
        events = store.executed('INSERT INTO agent_run_events')
        self.assertTrue(events and 'RUN_INTERRUPTED_BY_REVIEW' in events[0][1][4], '拒绝后要留可核对事件')

    def test_already_interrupted_run_is_idempotent(self):
        store = FakeStore([run_row('INTERRUPTED')])
        closed = store.abandon('turn-1')
        self.assertEqual('INTERRUPTED', closed['state'])
        self.assertEqual([], store.executed("state='INTERRUPTED'"), '已结束的轮次不再重复更新')

    def test_running_or_finished_run_is_never_abandoned(self):
        for state in ('PROCESSING', 'RESULT_READY', 'ACKED', 'COMPLETED'):
            store = FakeStore([run_row(state)])
            with self.assertRaises(ProtocolError) as error:
                store.abandon('turn-1')
            self.assertEqual('RUN_NOT_ABANDONABLE', str(error.exception), state)
            self.assertEqual([], store.executed("state='INTERRUPTED'"), '执行中或已完成不得被终止')

    def test_run_with_committed_result_is_protected(self):
        store = FakeStore([run_row('RECONCILIATION_REQUIRED', result='{"answer":"已核对"}')])
        with self.assertRaises(ProtocolError) as error:
            store.abandon('turn-1')
        self.assertEqual('RUN_HAS_RESULT', str(error.exception), '已有结果时不能用本入口作废')
        self.assertEqual([], store.executed("state='INTERRUPTED'"))

    def test_missing_run_reports_no_run(self):
        store = FakeStore([None])
        with self.assertRaises(ProtocolError) as error:
            store.abandon('turn-1')
        self.assertEqual('NO_RUN', str(error.exception))

    def test_blocking_lookup_uses_the_same_scope_as_accept(self):
        store = FakeStore([run_row('RECONCILIATION_REQUIRED')])
        store.blocking('binding-1')
        sql = store.cursor.log[0][0]
        for state in ('PROCESSING', 'RESULT_READY', 'RECONCILIATION_REQUIRED'):
            self.assertIn(state, sql, '阻塞判定必须与 accept 的可续状态一致')
        self.assertEqual(('binding-1',), store.cursor.log[0][1])


class RethreadTest(unittest.TestCase):
    def test_rethread_switches_thread_and_keeps_the_run(self):
        store = FakeStore([run_row('PROCESSING')])
        store.rethread('turn-1', 'binding-1', 'thread-new')
        updates = store.executed('UPDATE agent_runs SET thread_id')
        self.assertEqual(1, len(updates))
        self.assertEqual(('thread-new', 'turn-1'), updates[0][1])
        binding = store.executed('UPDATE agent_thread_bindings SET thread_id')
        self.assertEqual(1, len(binding))
        events = store.executed('INSERT INTO agent_run_events')
        self.assertIn('THREAD_REFRESHED', events[0][1][4])
        self.assertFalse([entry for entry in store.cursor.log if 'result_json' in entry[0] and entry[0].startswith('UPDATE')],
                         '换线程不得改写已保存的结果')

    def test_rethread_on_missing_run_is_rejected(self):
        store = FakeStore([None])
        with self.assertRaises(ProtocolError) as error:
            store.rethread('turn-1', 'binding-1', 'thread-new')
        self.assertEqual('NO_RUN', str(error.exception))


class RuntimeRestartTest(unittest.TestCase):
    def runtime(self):
        runtime = Runtime.__new__(Runtime)
        runtime.store = FakeStore([run_row('PROCESSING')])
        calls = {}

        class Graph:
            def invoke(self, payload, config):
                calls['payload'] = payload
                calls['config'] = config
                return {'answer': {'kind': 'general', 'answer': '重新规划后的回答'}}

        runtime.graph = Graph()
        return runtime, calls

    def test_restart_moves_to_a_fresh_thread_and_keeps_the_original_request(self):
        runtime, calls = self.runtime()
        request = {'turnId': 'turn-1', 'text': '上月餐饮支出多少', 'userId': 'u1'}
        prior = {'request': {'text': '更早的问题'}, 'plan': {'kind': 'analysis'}}
        output = runtime.restart(prior, 'turn-1', {'id': 'turn-1'}, {'id': 'binding-1'}, request)
        self.assertEqual('重新规划后的回答', output['answer']['answer'])
        switched = runtime.store.executed('UPDATE agent_runs SET thread_id')[0][1][0]
        self.assertEqual(switched, calls['config']['configurable']['thread_id'], '必须在新线程上重跑')
        self.assertEqual(request, calls['payload']['request'], '重跑沿用原请求，不改写客户问题')
        self.assertEqual(prior['request']['text'], calls['payload']['previous_question'], '上一问只作为上下文')
        self.assertEqual([], calls['payload']['receipts'], '重跑不带旧回执，避免复用过期事实')
        self.assertFalse(calls['payload']['validated'])
        self.assertNotEqual('thread-new', switched)

    def test_restart_never_touches_bank_actions_directly(self):
        runtime, _ = self.runtime()
        runtime.bank = None
        runtime.effects = None
        runtime.restart({'request': {'text': '旧问题'}}, 'turn-1', {'id': 'turn-1'}, {'id': 'binding-1'}, {'turnId': 'turn-1', 'text': '问题'})
        touched = [entry[0] for entry in runtime.store.cursor.log]
        self.assertFalse(any('INSERT INTO transactions' in sql or 'UPDATE accounts' in sql for sql in touched),
                         '重跑不直接改账；银行动作仍只在用户确认后执行')


if __name__ == '__main__':
    unittest.main()
