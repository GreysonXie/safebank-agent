import threading, unittest
import httpx
from safebank_agent.contracts import ProtocolError
from safebank_agent.server import AgentServer


class AbandonEndpointTest(unittest.TestCase):
    KEY = 'x' * 64

    def serve(self, store):
        class Settings:
            def snapshot(self): return {'keyConfigured': False, 'mode': 'mock'}

        class Adapter:
            settings = Settings()

        class Runtime:
            adapter = Adapter()
            def close(self): pass
        Runtime.store = store
        server = AgentServer(('127.0.0.1', 0), Runtime(), self.KEY)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        client = httpx.Client(base_url='http://127.0.0.1:' + str(server.server_address[1]))
        return server, thread, client

    def call(self, store, body=None):
        server, thread, client = self.serve(store)
        try:
            headers = {'Authorization': 'Bearer ' + self.KEY}
            return client.post('/internal/agent/runs/abandon', headers=headers,
                               json=body or {'userId': 'u1', 'conversationId': 'c1', 'turnId': 't1'})
        finally:
            client.close(); server.shutdown(); server.server_close(); thread.join()

    class Store:
        def __init__(self, binding=None, blocking=None, error=None):
            self.binding = binding; self.blocking_row = blocking; self.error = error; self.abandoned = []

        def binding_for(self, user, cid):
            return self.binding

        def blocking(self, bid):
            return self.blocking_row

        def abandon(self, rid):
            if self.error: raise ProtocolError(self.error)
            self.abandoned.append(rid)
            return {'id': rid, 'state': 'INTERRUPTED'}

    def test_unauthenticated_is_rejected(self):
        server, thread, client = self.serve(self.Store())
        try:
            self.assertEqual(client.post('/internal/agent/runs/abandon', json={}).status_code, 403)
        finally:
            client.close(); server.shutdown(); server.server_close(); thread.join()

    def test_no_binding_or_no_blocking_run_is_already_unblocked(self):
        for store in (self.Store(binding=None), self.Store(binding={'id': 'b1'}, blocking=None)):
            body = self.call(store).json()
            self.assertTrue(body['unblocked'])
            self.assertIn(body['reason'], ('NO_BINDING', 'NO_BLOCKING_RUN'))
            self.assertEqual(store.abandoned, [])

    def test_other_turn_blocking_is_reported_not_abandoned(self):
        store = self.Store(binding={'id': 'b1'},
                           blocking={'id': 'r9', 'java_turn_id': 't9', 'state': 'RECONCILIATION_REQUIRED'})
        body = self.call(store).json()
        self.assertFalse(body['unblocked'])
        self.assertEqual(body['reason'], 'OTHER_RUN')
        self.assertEqual(body['blockingTurnId'], 't9')
        self.assertEqual(store.abandoned, [])

    def test_matching_blocking_run_is_abandoned(self):
        store = self.Store(binding={'id': 'b1'},
                           blocking={'id': 'r1', 'java_turn_id': 't1', 'state': 'RECONCILIATION_REQUIRED'})
        body = self.call(store).json()
        self.assertTrue(body['unblocked'])
        self.assertEqual(body['reason'], 'ABANDONED')
        self.assertEqual(store.abandoned, ['r1'])

    def test_protected_state_is_not_abandoned(self):
        store = self.Store(binding={'id': 'b1'},
                           blocking={'id': 'r1', 'java_turn_id': 't1', 'state': 'RECONCILIATION_REQUIRED'},
                           error='RUN_HAS_RESULT')
        body = self.call(store).json()
        self.assertFalse(body['unblocked'])
        self.assertEqual(body['reason'], 'RUN_HAS_RESULT')

    def test_bad_body_is_rejected(self):
        response = self.call(self.Store(binding={'id': 'b1'}), body={'userId': 'u1'})
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.json()['code'], 'INVALID_BODY')


if __name__ == '__main__':
    unittest.main()
