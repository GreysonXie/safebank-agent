"""失败终结口径：只有"可能已发出银行调用、结果未知"才允许暂停；其余失败必须终结本轮，不占住会话。"""
import unittest
from contextlib import contextmanager

from safebank_agent.contracts import ProtocolError, project
from safebank_agent.store import Lease, Store


class FakeCursor:
    def __init__(self, run, receipts):
        self.run = run
        self.receipts = receipts
        self.log = []
        self.rowcount = 1
        self._next = None
        self._all = []

    def execute(self, sql, params=None):
        normalized = ' '.join(sql.split())
        self.log.append((normalized, params))
        self.rowcount = 1
        if normalized.startswith('SELECT * FROM agent_runs WHERE id=%s AND lease_owner'):
            self._next = self.run
        elif 'MAX(sequence)' in normalized:
            self._next = {'n': 1}
        elif 'FROM agent_effect_receipts' in normalized:
            self._all = self.receipts
            self._next = self.receipts[0] if self.receipts else None
        else:
            self._next = None

    def fetchone(self):
        return self._next

    def fetchall(self):
        return self._all


class FakeStore(Store):
    def __init__(self, run, receipts):
        self.run = run
        self.receipts = receipts
        self.cursor = None

    @contextmanager
    def transaction(self):
        self.cursor = FakeCursor(self.run, self.receipts)
        yield self.cursor

    def writes(self):
        return [(sql, params) for sql, params in self.cursor.log if sql.startswith('UPDATE agent_runs')]


def running_run():
    return {'id': 'run-1', 'state': 'PROCESSING', 'lease_owner': 'owner', 'lease_revision': 1,
            'binding_id': 'binding-1', 'java_turn_id': 'run-1'}


LEASE = Lease('run-1', 'owner', 1)


class FailureTerminalTest(unittest.TestCase):
    def pause(self, receipts, code='ANSWER_REPAIR_EXHAUSTED'):
        store = FakeStore(running_run(), receipts)
        store.pause(LEASE, code)
        return store

    def test_model_only_failure_ends_the_turn_instead_of_pausing(self):
        store = self.pause([{'purpose': 'model', 'state': 'UNKNOWN'}], 'MODEL_EFFECT_UNKNOWN')
        updates = store.writes()
        self.assertEqual(1, len(updates))
        self.assertIn("state='FAILED'", updates[0][0], '没有银行写操作时记 FAILED，客户可继续提问')
        self.assertIn('MODEL_EFFECT_UNKNOWN', updates[0][1])
        events = [params[4] for sql, params in store.cursor.log if 'INSERT INTO agent_run_events' in sql]
        self.assertIn('RUN_FAILED_NO_BANK_EFFECT', events)

    def test_answer_validation_failure_is_terminal_too(self):
        store = self.pause([{'purpose': 'model', 'state': 'SUCCEEDED'}], 'ANSWER_REPAIR_EXHAUSTED')
        self.assertIn("state='FAILED'", store.writes()[0][0])

    def test_failure_without_any_effect_is_terminal(self):
        store = self.pause([], 'INVALID_PLAN')
        self.assertIn("state='FAILED'", store.writes()[0][0])

    def test_uncertain_bank_call_still_requires_review(self):
        for purpose in ('tool', 'validate'):
            for state in ('SENDING', 'UNKNOWN'):
                store = self.pause([{'purpose': 'model', 'state': 'SUCCEEDED'}, {'purpose': purpose, 'state': state}], 'BANK_CALLBACK_UNAVAILABLE')
                updates = store.writes()
                self.assertIn("state='RECONCILIATION_REQUIRED'", updates[0][0], f'{purpose}/{state} 必须人工核对')
                events = [params[4] for sql, params in store.cursor.log if 'INSERT INTO agent_run_events' in sql]
                self.assertIn('RUN_PAUSED', events)

    def test_finished_bank_call_does_not_force_review(self):
        store = self.pause([{'purpose': 'tool', 'state': 'SUCCEEDED'}, {'purpose': 'validate', 'state': 'FAILED'}], 'BANK_CALLBACK_REJECTED')
        self.assertIn("state='FAILED'", store.writes()[0][0], '银行调用已有确定结果时不需要人工核对')

    def test_lost_lease_cannot_flip_a_turn(self):
        class LostLease(Store):
            def __init__(self):
                pass

            @contextmanager
            def transaction(self):
                cursor = FakeCursor(None, [])
                yield cursor
                self.cursor = cursor
        store = LostLease()
        with self.assertRaises(ProtocolError) as error:
            store.pause(LEASE, 'MODEL_EFFECT_UNKNOWN')
        self.assertEqual('LEASE_LOST', str(error.exception))


if __name__ == '__main__':
    unittest.main()
