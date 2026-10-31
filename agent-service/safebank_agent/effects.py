"""Durable network effect journal, independent of graph checkpoint writes."""
import json
import re
from .contracts import ProtocolError, ModelRequestRejected, canonical, digest


class Effects:
    def __init__(self, store):
        self.store = store

    def call(self, lease, node, sequence, purpose, input_value, operation, reserve=None):
        if purpose not in ('model', 'tool', 'validate') or type(sequence) is not int or sequence < 0:
            raise ProtocolError('INVALID_EFFECT')
        key = digest([lease.run_id, node, sequence, purpose])
        input_hash = digest(input_value)
        unknown = False
        with self.store.transaction() as c:
            run = self.store.guard(c, lease)
            c.execute('SELECT * FROM agent_effect_receipts WHERE operation_key=%s FOR UPDATE', (key,))
            receipt = c.fetchone()
            if receipt:
                if receipt['input_hash'] != input_hash:
                    raise ProtocolError('EFFECT_CONFLICT')
                if receipt['state'] == 'SUCCEEDED':
                    return json.loads(receipt['result_json'])
                if receipt['state'] == 'FAILED':
                    raise ProtocolError('EFFECT_FAILED')
                if purpose == 'model' and receipt['state'] in ('SENDING', 'UNKNOWN'):
                    c.execute("UPDATE agent_effect_receipts SET state='UNKNOWN',updated_at=UTC_TIMESTAMP(6) WHERE operation_key=%s", (key,))
                    unknown = True
            else:
                c.execute("INSERT INTO agent_effect_receipts(operation_key,origin_run_id,binding_id,origin_java_turn_id,node_name,logical_sequence,purpose,input_hash,state,lease_revision,created_at,updated_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,'PREPARED',%s,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                          (key, run['id'], run['binding_id'], run['java_turn_id'], node, sequence, purpose, input_hash, lease.revision))
        if unknown:
            raise ProtocolError('MODEL_EFFECT_UNKNOWN')
        # Idempotent reservation must complete before SENDING. A lost reservation
        # response leaves PREPARED; recovery requests the original reservation.
        reservation = reserve(key, input_hash) if reserve else None
        with self.store.transaction() as c:
            self.store.guard(c, lease)
            c.execute('SELECT state,reservation_id FROM agent_effect_receipts WHERE operation_key=%s FOR UPDATE', (key,))
            receipt = c.fetchone()
            if purpose == 'model' and receipt['state'] != 'PREPARED':
                raise ProtocolError('MODEL_EFFECT_UNKNOWN')
            if receipt['reservation_id'] and reservation != receipt['reservation_id']:
                raise ProtocolError('RESERVATION_CONFLICT')
            c.execute("UPDATE agent_effect_receipts SET state='SENDING',reservation_id=%s,lease_revision=%s,updated_at=UTC_TIMESTAMP(6) WHERE operation_key=%s", (reservation, lease.revision, key))
            self.store.event(c,lease.run_id,node,'EFFECT_SENDING')
            if reservation:
                c.execute("INSERT IGNORE INTO agent_model_requests(id,run_id,reservation_id,purpose,state,reserved_at) VALUES(%s,%s,%s,%s,'RESERVED',UTC_TIMESTAMP(6))", (key, lease.run_id, reservation, node))
        try:
            result = operation(key)
            encoded = canonical(result)
            if len(encoded.encode('utf-8')) > 262144:
                raise ProtocolError('EFFECT_RESULT_TOO_LARGE')
        except Exception as error:
            rejected = purpose == 'model' and isinstance(error, ModelRequestRejected)
            # A transport error cannot prove that the provider did not receive it.
            with self.store.transaction() as c:
                self.store.guard(c, lease)
                c.execute("UPDATE agent_effect_receipts SET state=%s,updated_at=UTC_TIMESTAMP(6) WHERE operation_key=%s", ('UNKNOWN' if purpose == 'model' and not rejected else 'FAILED', key))
                if reservation:
                    # 记录真实原因码（只留协议码，不留异常文本），便于事后区分超时与内容错误。
                    reason=re.sub(r'[^A-Z0-9_]+','_',str(error))[:64] or 'MODEL_SEND_UNCERTAIN'
                    c.execute("UPDATE agent_model_requests SET state=%s,error_code=%s,finished_at=UTC_TIMESTAMP(6) WHERE reservation_id=%s", ('FAILED' if rejected else 'UNKNOWN', str(error) if rejected else reason, reservation))
            if rejected:
                raise error from None
            raise ProtocolError('MODEL_EFFECT_UNKNOWN' if purpose == 'model' else 'EFFECT_FAILED') from None
        with self.store.transaction() as c:
            self.store.guard(c, lease)
            c.execute("UPDATE agent_effect_receipts SET state='SUCCEEDED',result_json=%s,result_hash=%s,updated_at=UTC_TIMESTAMP(6) WHERE operation_key=%s AND state='SENDING' AND lease_revision=%s", (encoded, digest(result), key, lease.revision))
            if c.rowcount != 1:
                raise ProtocolError('EFFECT_CONFLICT')
            self.store.event(c,lease.run_id,node,'EFFECT_SAVED')
            if reservation:
                usage = result.get('usage') or {}
                def token(field):
                    value = usage.get(field)
                    return value if type(value) is int and 0 <= value <= 9223372036854775807 else None
                c.execute("UPDATE agent_model_requests SET state='SUCCEEDED',provider_response_id=%s,actual_model=%s,prompt_tokens=%s,completion_tokens=%s,cache_hit_tokens=%s,cache_miss_tokens=%s,finished_at=UTC_TIMESTAMP(6) WHERE reservation_id=%s",
                          (str(result.get('id',''))[:160], str(result.get('model',''))[:120], token('prompt_tokens'), token('completion_tokens'), token('prompt_cache_hit_tokens'), token('prompt_cache_miss_tokens'), reservation))
        return result
