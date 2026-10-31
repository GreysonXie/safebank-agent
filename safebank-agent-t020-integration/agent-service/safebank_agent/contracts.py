"""Persistable projections: transport credentials are deliberately excluded."""
import hashlib
import json
import re


class ProtocolError(RuntimeError):
    pass


class ModelRequestRejected(ProtocolError):
    """Complete, unambiguous validation/auth rejection, not a transport failure."""
    STATUSES = frozenset((400, 401, 403, 404, 405, 413, 415, 422))

    def __init__(self, status):
        if type(status) is not int or status not in self.STATUSES:
            raise ValueError('Not an unambiguous model request rejection')
        super().__init__('MODEL_REQUEST_REJECTED_' + str(status))


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()


def bank_hash(tool, value):
    return hashlib.sha256((tool + '\n' + canonical(value)).encode('utf-8')).hexdigest()


def project(request):
    fields = ('userId', 'conversationId', 'turnId', 'requestId', 'expectedVersion', 'text')
    safe = {k: request[k] for k in fields}
    limit=request.get('modelRequestLimit',5)
    if type(limit) is not int or not 1<=limit<=5:raise ProtocolError('INVALID_MODEL_LIMIT')
    # Omitted/default-five projections remain byte-compatible with prior M3 runs.
    if limit<5:safe['modelRequestLimit']=limit
    for k in fields[:4]:
        if not isinstance(safe[k], str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', safe[k]):
            raise ProtocolError('INVALID_ID')
    if len(safe['userId']) > 40 or type(safe['expectedVersion']) is not int or safe['expectedVersion'] < 1:
        raise ProtocolError('INVALID_VERSION')
    if not isinstance(safe['text'], str) or not 1 <= len(safe['text']) <= 4500:
        raise ProtocolError('INVALID_TEXT')
    return safe
