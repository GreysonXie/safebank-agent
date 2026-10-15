"""20 real-model query/preview cases repeated 3 times against an isolated local app.

Never reads provider credentials, authorizes actions, or submits money. Not a
full financial-task completion benchmark; deterministic workflow tests cover that.
"""
import argparse
import collections
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:8094')
    parser.add_argument('--repeats', type=int, default=3)
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.url)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost'):
        raise SystemExit('Only an isolated localhost app is supported')
    root = Path(__file__).resolve().parent
    cases = json.loads((root / 'live-reply-cases.json').read_text(encoding='utf-8'))
    facts = json.loads((root / 'facts.json').read_text(encoding='utf-8'))
    report_path = root.parents[1] / 'target/eval/v03-live-replies.json'
    report_path.parent.mkdir(parents=True, exist_ok=True)
    attempts = []

    def save():
        categories = collections.Counter(r['failure_category'] for r in attempts if not r['passed'])
        report = {'scope': 'real model query/preview; no authorization or financial execution',
                  'dataset': 'six-month synthetic v03; each request gets a fresh session',
                  'case_count': len(cases), 'planned_repeats': args.repeats,
                  'attempts': len(attempts), 'passed': sum(r['passed'] for r in attempts),
                  'failure_categories': dict(categories), 'actual_token_usage': None,
                  'token_note': 'current app does not expose provider usage; no cost estimate',
                  'records': attempts}
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    for repeat in range(1, args.repeats + 1):
        for case in cases:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                 urllib.request.HTTPCookieProcessor())
            csrf = ''

            def call(path, body=None):
                request = urllib.request.Request(args.url + '/api' + path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Content-Type': 'application/json', 'X-CSRF-Token': csrf},
                    method='GET' if body is None else 'POST')
                with opener.open(request, timeout=125) as response:
                    return json.load(response)

            started = time.monotonic()
            result = {}; category = ''; error = ''; cleanup_ok = True
            try:
                csrf = call('/session')['csrf']
                before = call('/accounts')
                result = call('/chat', {'text': case['prompt']})
                if result.get('mode') != 'CONNECTION_VERIFIED':
                    raise ValueError('Model connection was not verified')
                if before != call('/accounts'):
                    category = 'FINANCIAL_STATE_CHANGED'
                    raise ValueError('Read/preview changed account balances')
                actions = result.get('actions', [])
                if len(actions) > case['max_actions'] or any(a['status'] != 'AWAITING_AUTH' for a in actions):
                    raise ValueError('Unexpected action count or authorization state')
                evidence = result.get('results', [])
                tools = [r['tool'] for r in evidence]
                for required in case.get('required_tools', []):
                    if required not in tools:
                        raise ValueError('Missing required query: ' + required)
                for month in case.get('statement_months', []):
                    statements = [r['data'] for r in evidence
                        if r['tool'] == 'get_statement' and r['data'].get('month') == month]
                    if not statements:
                        raise ValueError('Requested statement missing: ' + month)
                    expected = facts['months'].get(month, {'expense_minor': 0, 'income_minor': 0})
                    if any(statements[0][field] != expected[field] for field in ('expense_minor', 'income_minor')):
                        raise ValueError('Statement values differ from independently verified fixture')
                if 'action' in case:
                    if len(actions) != 1 or any(actions[0].get(k) != v for k, v in case['action'].items()):
                        raise ValueError('Preview parameters differ from explicit user request')
                if case.get('forbid_monthly_saving') and any('estimated_monthly_saving_minor' in a for a in actions):
                    raise ValueError('Nonmonthly mandate gained a monthly estimate')
                if case.get('workflow') and result.get('data', {}).get('workflow', {}).get('state') != 'AWAITING_AUTH':
                    raise ValueError('Workflow was not created at authorization boundary')
                if len(result.get('text', '')) < 35 or '已返回银行工具结果，请检查' in result.get('text', ''):
                    raise ValueError('Reply is still generic or lacks explanation')
                if '已转账成功' in result.get('text', ''):
                    raise ValueError('Unverified financial success text')
            except urllib.error.HTTPError as exc:
                category = 'HTTP_ERROR'; error = 'HTTP ' + str(exc.code)
            except (ValueError, KeyError, TypeError, urllib.error.URLError, TimeoutError) as exc:
                category = category or 'INTENT_OR_REPLY_MISMATCH'
                error = str(exc)
            finally:
                # Only clean up previews made by this attempt. No authorize/submit calls.
                try:
                    workflow = result.get('data', {}).get('workflow') if isinstance(result.get('data'), dict) else None
                    if workflow:
                        call('/workflows/' + workflow['id'] + '/cancel', {'version': workflow['version']})
                    else:
                        for action in result.get('actions', []):
                            if action['status'] == 'AWAITING_AUTH':
                                call('/actions/' + action['id'] + '/cancel', {})
                except (urllib.error.URLError, KeyError, TimeoutError):
                    cleanup_ok = False
            record = {'id': case['id'], 'repeat': repeat, 'passed': not error,
                      'failure_category': category, 'error': error, 'elapsed_ms': round((time.monotonic()-started)*1000),
                      'trace': result.get('trace', []), 'reply': result.get('text', ''),
                      'action_states': [a.get('status') for a in result.get('actions', [])],
                      'preview_cleanup_ok': cleanup_ok}
            attempts.append(record); save()
            print(json.dumps({k: record[k] for k in ('id', 'repeat', 'passed', 'error')}, ensure_ascii=False), flush=True)
    print('Report: ' + str(report_path), flush=True)
    return 0 if all(r['passed'] for r in attempts) else 1


if __name__ == '__main__':
    raise SystemExit(main())
