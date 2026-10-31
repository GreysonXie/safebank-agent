"""MySQL ownership, immutable results and fenced worker leases.

Every method opens a short transaction; network operations never occur here.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import uuid
import pymysql
from .contracts import ProtocolError, canonical, digest, project, bank_hash


@dataclass(frozen=True)
class Lease:
    run_id: str
    owner: str
    revision: int


class Store:
    def __init__(self, config):
        self.config = dict(config, cursorclass=pymysql.cursors.DictCursor)

    @contextmanager
    def transaction(self):
        connection = pymysql.connect(**self.config, autocommit=False)
        try:
            with connection.cursor() as cursor:
                yield cursor
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def accept(self, request):
        safe = project(request)
        with self.transaction() as c:
            c.execute('INSERT IGNORE INTO agent_thread_bindings(id,user_id,conversation_id,thread_id,created_at,updated_at) VALUES(%s,%s,%s,%s,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))',
                      (uuid.uuid4().hex, safe['userId'], safe['conversationId'], uuid.uuid4().hex))
            c.execute('SELECT * FROM agent_thread_bindings WHERE user_id=%s AND conversation_id=%s FOR UPDATE', (safe['userId'], safe['conversationId']))
            binding = c.fetchone()
            c.execute('SELECT * FROM agent_runs WHERE java_turn_id=%s OR (binding_id=%s AND request_id=%s) FOR UPDATE',
                      (safe['turnId'], binding['id'], safe['requestId']))
            previous = c.fetchone()
            if previous:
                if previous['binding_id'] != binding['id'] or previous['request_hash'] != digest(safe):
                    raise ProtocolError('REQUEST_CONFLICT')
                return previous
            c.execute("SELECT id FROM agent_runs WHERE binding_id=%s AND state IN ('PROCESSING','RESULT_READY','RECONCILIATION_REQUIRED') LIMIT 1 FOR UPDATE", (binding['id'],))
            if c.fetchone():
                raise ProtocolError('ACTIVE_RUN')
            # A reset/version discontinuity starts a new graph; historical checkpoints remain.
            if binding['last_output_version'] is not None and binding['last_output_version'] != safe['expectedVersion']:
                binding['thread_id'] = uuid.uuid4().hex
                binding['waiting_run_id'] = None
                c.execute('UPDATE agent_thread_bindings SET thread_id=%s,waiting_run_id=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=%s', (binding['thread_id'], binding['id']))
            c.execute("INSERT INTO agent_runs(id,binding_id,java_turn_id,request_id,request_hash,request_json,thread_id,input_version,state,created_at,updated_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,'PROCESSING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                      (safe['turnId'], binding['id'], safe['turnId'], safe['requestId'], digest(safe), canonical(safe), binding['thread_id'], safe['expectedVersion']))
            c.execute('SELECT * FROM agent_runs WHERE id=%s', (safe['turnId'],))
            return c.fetchone()

    def get(self, run_id):
        with self.transaction() as c:
            c.execute('SELECT * FROM agent_runs WHERE id=%s', (run_id,))
            return c.fetchone()

    def binding(self, binding_id):
        with self.transaction() as c:
            c.execute('SELECT * FROM agent_thread_bindings WHERE id=%s', (binding_id,))
            return c.fetchone()

    def binding_for(self, user_id, conversation_id):
        with self.transaction() as c:
            c.execute('SELECT * FROM agent_thread_bindings WHERE user_id=%s AND conversation_id=%s FOR UPDATE', (user_id, conversation_id))
            return c.fetchone()

    def blocking(self, binding_id):
        """与 accept 的 ACTIVE_RUN 判定同一口径，用来定位真正占用会话的那一条 run。"""
        with self.transaction() as c:
            c.execute("SELECT * FROM agent_runs WHERE binding_id=%s AND state IN ('PROCESSING','RESULT_READY','RECONCILIATION_REQUIRED') ORDER BY created_at,id LIMIT 1 FOR UPDATE", (binding_id,))
            return c.fetchone()

    def rethread(self, run_id, binding_id, thread_id):
        """无主可续的陈旧检查点：为该轮与绑定换新线程，避免历史残点永久阻塞会话。

        只改线程绑定，不改请求、不改结果、不触发任何银行动作；随后由 runtime 从头重跑本轮。
        """
        with self.transaction() as c:
            c.execute('SELECT * FROM agent_runs WHERE id=%s FOR UPDATE', (run_id,))
            run = c.fetchone()
            if not run:
                raise ProtocolError('NO_RUN')
            c.execute("UPDATE agent_runs SET thread_id=%s,updated_at=UTC_TIMESTAMP(6) WHERE id=%s", (thread_id, run_id))
            c.execute("UPDATE agent_thread_bindings SET thread_id=%s,waiting_run_id=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=%s", (thread_id, binding_id))
            self.event(c, run_id, 'runtime', 'THREAD_REFRESHED')

    def abandon(self, run_id):
        """受保护终态：只把无结果、待人工核对的 run 置为 INTERRUPTED。

        幂等、保留请求/回执/错误码/银行关联；已有结果或仍在执行的一律拒绝。
        所有 worker 写入都经过 guard()/ack() 的状态校验，终止态不会被写回。
        """
        with self.transaction() as c:
            c.execute('SELECT * FROM agent_runs WHERE id=%s FOR UPDATE', (run_id,))
            run = c.fetchone()
            if not run:
                raise ProtocolError('NO_RUN')
            if run['state'] == 'INTERRUPTED':
                return run
            if run['state'] != 'RECONCILIATION_REQUIRED':
                raise ProtocolError('RUN_NOT_ABANDONABLE')
            if run['result_json']:
                raise ProtocolError('RUN_HAS_RESULT')
            c.execute("UPDATE agent_runs SET state='INTERRUPTED',lease_owner=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=%s AND state='RECONCILIATION_REQUIRED'", (run_id,))
            if c.rowcount != 1:
                raise ProtocolError('RUN_NOT_ABANDONABLE')
            self.event(c, run_id, 'runtime', 'RUN_INTERRUPTED_BY_REVIEW')
            c.execute('SELECT * FROM agent_runs WHERE id=%s', (run_id,))
            return c.fetchone()

    def claim(self, run_id, owner):
        with self.transaction() as c:
            c.execute("UPDATE agent_runs SET lease_owner=%s,lease_revision=lease_revision+1,lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6) WHERE id=%s AND state='PROCESSING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))", (owner, run_id))
            if c.rowcount != 1:
                raise ProtocolError('LEASE_BUSY')
            self.event(c,run_id,'runtime','LEASE_CLAIMED')
            c.execute('SELECT lease_revision FROM agent_runs WHERE id=%s', (run_id,))
            return Lease(run_id, owner, c.fetchone()['lease_revision'])

    def event(self,c,run_id,node,code):
        """Caller already holds the run lock; only safe event codes are saved."""
        c.execute('SELECT COALESCE(MAX(sequence),0)+1 n FROM agent_run_events WHERE run_id=%s',(run_id,))
        sequence=c.fetchone()['n']
        c.execute('INSERT INTO agent_run_events(id,run_id,sequence,node_name,event_code,recorded_at) VALUES(%s,%s,%s,%s,%s,UTC_TIMESTAMP(6))',(uuid.uuid4().hex,run_id,sequence,node,code))

    def guard(self, c, lease):
        c.execute("SELECT * FROM agent_runs WHERE id=%s AND lease_owner=%s AND lease_revision=%s AND lease_until>UTC_TIMESTAMP(6) AND state='PROCESSING' FOR UPDATE", (lease.run_id, lease.owner, lease.revision))
        row = c.fetchone()
        if not row:
            raise ProtocolError('LEASE_LOST')
        return row

    def check(self, lease):
        with self.transaction() as c:
            self.guard(c, lease)

    def heartbeat(self, lease):
        with self.transaction() as c:
            self.guard(c, lease)
            c.execute('UPDATE agent_runs SET lease_until=TIMESTAMPADD(SECOND,60,UTC_TIMESTAMP(6)) WHERE id=%s', (lease.run_id,))

    def result(self, lease, result, kind, interrupt_id=None):
        if result.get('turnId') != lease.run_id or kind not in ('analysis','clarification','general'):
            raise ProtocolError('INVALID_RESULT')
        with self.transaction() as c:
            run = self.guard(c, lease)
            if result.get('inputVersion') != run['input_version']:
                raise ProtocolError('INVALID_VERSION')
            c.execute("UPDATE agent_runs SET state='RESULT_READY',result_json=%s,result_hash=%s,result_kind=%s,interrupt_id=%s,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=%s",
                      (canonical(result), bank_hash('agent_result',result), kind, interrupt_id, lease.run_id))
            self.event(c,lease.run_id,'runtime','RESULT_SAVED')

    def ack(self, run_id, output_version, result_hash):
        with self.transaction() as c:
            c.execute('SELECT binding_id FROM agent_runs WHERE id=%s', (run_id,))
            initial = c.fetchone()
            if not initial:
                raise ProtocolError('NO_RUN')
            c.execute('SELECT * FROM agent_thread_bindings WHERE id=%s FOR UPDATE', (initial['binding_id'],))
            binding = c.fetchone()
            c.execute('SELECT * FROM agent_runs WHERE id=%s FOR UPDATE', (run_id,))
            run = c.fetchone()
            if type(output_version) is not int or output_version != run['input_version'] + 1 or result_hash != run['result_hash']:
                raise ProtocolError('ACK_CONFLICT')
            if run['state'] in ('COMPLETED','NEEDS_CLARIFICATION'):
                return run
            if run['state'] != 'RESULT_READY' or binding['thread_id'] != run['thread_id']:
                raise ProtocolError('ACK_CONFLICT')
            state = 'NEEDS_CLARIFICATION' if run['result_kind'] == 'clarification' else 'COMPLETED'
            c.execute('UPDATE agent_runs SET state=%s,output_version=%s,acknowledged_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=%s', (state, output_version, run_id))
            c.execute('UPDATE agent_thread_bindings SET last_output_version=%s,waiting_run_id=%s,updated_at=UTC_TIMESTAMP(6) WHERE id=%s', (output_version, run_id if state == 'NEEDS_CLARIFICATION' else None, binding['id']))
            self.event(c,run_id,'runtime','JAVA_ACK_ACCEPTED')
            run.update(state=state, output_version=output_version)
            return run

    def pause(self, lease, code):
        with self.transaction() as c:
            self.guard(c, lease)
            c.execute("UPDATE agent_runs SET state='RECONCILIATION_REQUIRED',error_code=%s,lease_until=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=%s", (code, lease.run_id))
            self.event(c,lease.run_id,'runtime','RUN_PAUSED')

    def defer_prepared(self,lease):
        with self.transaction() as c:
            self.guard(c,lease)
            c.execute("SELECT state FROM agent_effect_receipts WHERE origin_run_id=%s AND purpose='model' FOR UPDATE",(lease.run_id,))
            states=[r['state'] for r in c.fetchall()]
            if 'PREPARED' not in states or any(s in ('SENDING','UNKNOWN','FAILED') for s in states):return False
            c.execute("UPDATE agent_runs SET lease_owner=NULL,lease_until=NULL,error_code='RESERVATION_RESPONSE_UNCERTAIN',updated_at=UTC_TIMESTAMP(6) WHERE id=%s",(lease.run_id,))
            self.event(c,lease.run_id,'runtime','PRESEND_RECOVERY_REQUIRED')
            return True

    def expired(self):
        with self.transaction() as c:
            c.execute("SELECT id FROM agent_runs WHERE state='PROCESSING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) ORDER BY lease_until,id LIMIT 100")
            return [r['id'] for r in c.fetchall()]
