"""M2 protocol fixture, not LangGraph or a production model service.

Only synthetic loopback data. No model API, no paid calls, no raw token logs.
Runs live in RAM; restart returns NO_RUN instead of pretending persistence.
"""
import hashlib
import hmac
import json
import os
import re
import threading
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = os.environ['SAFEBANK_AGENT_SERVICE_KEY']
JAVA = os.environ.get('SAFEBANK_AGENT_JAVA_URL', 'http://127.0.0.1:8132')
PORT = int(os.environ.get('SAFEBANK_FAKE_AGENT_PORT', '8133'))
if len(KEY) < 64 or not re.fullmatch(r'http://127\.0\.0\.1:\d+', JAVA):
    raise RuntimeError('Explicit loopback service configuration required')
RUNS = {}
LOCK = threading.RLock()

def java(path, body, token):
    request = urllib.request.Request(JAVA + path, data=json.dumps(body, ensure_ascii=False).encode(),
        headers={'Authorization': 'Bearer ' + KEY, 'X-Agent-Grant': token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=3) as response:
        # Bank facts include both monthly catalogs; the run/ACK channel remains 64 KiB.
        data = response.read(262145)
        if len(data) > 262144:
            raise RuntimeError('Java response too large')
        return json.loads(data)

def tool(turn, token, name, args):
    canonical = json.dumps(args, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    return java('/internal/agent/tools', {'operationKey': hashlib.sha256((turn + '/query/0').encode()).hexdigest(),
        'inputHash': hashlib.sha256((name + '\n' + canonical).encode()).hexdigest(), 'tool': name, 'args': args}, token)

def run(body):
    token = body['grant']  # Only this stack frame; never saved in RUNS/result.
    months = re.findall(r'20\d{2}-(?:0[1-9]|1[0-2])', body['text'])
    evidence = []
    answer = {'kind': 'clarification', 'answer': '当前正在验证网关协议。请说明要查询的月份或账户余额。'}
    if months:
        name = 'statement_compare' if len(months) >= 2 else 'statement'
        args = {'month': months[0]}
        if len(months) >= 2:
            args['comparisonMonth'] = months[1]
        receipt = tool(body['turnId'], token, name, args)
        evidence.append(receipt['evidenceId'])
        facts = [k for k in receipt['facts'] if k.endswith('.expense_minor')]
        if name == 'statement_compare':
            answer = {'kind': 'analysis', 'answer': '两个查询月份的支出分别为{{' + facts[0] + '}}和{{' + facts[1] + '}}。以上来自各自账单快照；此页面用于验证跨服务协议。'}
        else:
            answer = {'kind': 'analysis', 'answer': '查询月份的支出合计为{{' + facts[0] + '}}。本人账户内部转账不计入该支出合计；这是银行合成账单的查询快照。'}
    elif '余额' in body['text'] or '账户' in body['text']:
        receipt = tool(body['turnId'], token, 'account_query', {})
        evidence.append(receipt['evidenceId'])
        facts = [k for k in receipt['facts'] if k.endswith('.balance_minor')]
        if facts:
            answer = {'kind': 'analysis', 'answer': '本次查询到的账户余额分别为' + '、'.join('{{' + k + '}}' for k in facts) + '。余额为查询时快照，请按账户页面核对。'}
    result = {'turnId': body['turnId'], 'inputVersion': body['expectedVersion'], 'answer': answer, 'evidenceIds': evidence}
    validated = java('/internal/agent/answers/validate', result, token)
    return {'state': 'RESULT_READY', 'result': result, 'resultHash': validated['resultHash']}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, status, data):
        payload = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status); self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)

    def handle_request(self):
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + KEY):
            return self.respond(403, {'code': 'INTERNAL_AUTH_REQUIRED'})
        match = re.fullmatch(r'/internal/agent/turns(?:/([A-Za-z0-9_-]{1,80})(/ack)?)?', self.path)
        if not match:
            return self.respond(404, {'code': 'NOT_FOUND'})
        turn, ack = match.groups()
        if self.command == 'GET' and turn and not ack:
            with LOCK:
                return self.respond(200, RUNS.get(turn, {'state': 'NO_RUN'}))
        length = int(self.headers.get('Content-Length', '-1'))
        if not 0 <= length <= 65536:
            return self.respond(413, {'code': 'TOO_LARGE'})
        body = json.loads(self.rfile.read(length))
        if self.command != 'POST':
            return self.respond(405, {'code': 'METHOD_NOT_ALLOWED'})
        with LOCK:
            if ack:
                saved = RUNS.get(turn)
                if not saved or body.get('turnId') != turn or body.get('resultHash') != saved.get('resultHash') or body.get('outputVersion') != saved['result']['inputVersion'] + 1:
                    return self.respond(409, {'code': 'ACK_CONFLICT'})
                saved['state'] = 'COMPLETED'; saved['outputVersion'] = body['outputVersion']
                return self.respond(200, saved)
            if turn or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', body.get('turnId', '')):
                return self.respond(400, {'code': 'INVALID_TURN'})
            turn = body['turnId']
            identity = {k: body[k] for k in ('userId', 'conversationId', 'turnId', 'requestId', 'expectedVersion', 'text')}
            digest = hashlib.sha256(json.dumps(identity, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
            if turn in RUNS:
                if RUNS[turn]['inputHash'] != digest:
                    return self.respond(409, {'code': 'INPUT_CONFLICT'})
                return self.respond(200, RUNS[turn])
            RUNS[turn] = {'state': 'PROCESSING', 'inputHash': digest}
            try:
                saved = run(body); saved['inputHash'] = digest; RUNS[turn] = saved
                return self.respond(200, saved)
            except Exception as error:
                RUNS[turn]['state'] = 'UNKNOWN'
                # Fixed diagnostic codes only; never save headers, token or request body.
                reason = 'JAVA_RESPONSE_TOO_LARGE' if isinstance(error, RuntimeError) and str(error) == 'Java response too large' else 'PROTOCOL_FAILURE'
                if isinstance(error, urllib.error.HTTPError):
                    try:
                        reason = json.loads(error.read(65536)).get('code', 'JAVA_CALLBACK_REJECTED')
                    except (ValueError, OSError):
                        reason = 'JAVA_CALLBACK_REJECTED'
                RUNS[turn]['errorCode'] = reason
                return self.respond(503, {'code': 'RUN_REQUIRES_REVIEW'})

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        try:
            self.handle_request()
        except (ValueError, KeyError):
            self.respond(400, {'code': 'INVALID_REQUEST'})

if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
