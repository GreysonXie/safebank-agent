const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createHash} = require('node:crypto');
const {JSDOM} = require('jsdom');

const root = path.resolve(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const session = {csrf: 'synthetic-csrf', user: 'synthetic-user', mode: 'OFFLINE_DEMO', faultsEnabled: false};
const recommendation = {
  source: '测试合成目录', asOf: '2026-09-30', catalogVersion: 'synthetic-test',
  generatedAt: '2026-10-27T00:00:00Z', periodStart: '2025-09-30', periodEnd: '2026-09-30',
  summary: '一个匹配的测试方向', disclosure: '仅用于测试的虚构示例。', rules: ['检查风险、期限与流动性。'], exclusions: [],
  candidates: [{
    product: {
      id: 'TEST-DEPOSIT', name: '测试存款方向', category: '存款', description: '虚构测试事实', riskLevel: 1,
      riskLabel: '低波动', periodReturnBps: null, maxDrawdownBps: null, annualFeeBps: null,
      minimumAmountMinor: 100, minimumHorizonMonths: 1, capitalProtected: true, lockMonths: 0,
      liquidityNote: '测试流动性说明', riskNote: '测试风险说明'
    }, reasons: ['符合测试条件']
  }]
};

function boot(t, {url = 'https://safebank.invalid/', respond, cache = new Map()} = {}) {
  // Execute the actual page's deferred scripts in order, with no network or replacement UI globals.
  const dom = new JSDOM(html, {url, runScripts: 'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, context = dom.getInternalVMContext(), calls = [];
  const defaults = {
    '/api/session': session,
    '/api/users/profile': {name: '测试用户', phone: '', gender: 'UNSPECIFIED'},
    '/api/models/providers': [{id: 'local', name: '测试', icon: 'custom', baseUrl: 'https://model.invalid', models: ['demo']}],
    '/api/models/configuration': {provider: 'local', baseUrl: 'https://model.invalid', model: 'demo', enabled: false, hasApiKey: false},
    '/api/finance/recommendations': recommendation
  };
  w.fetch = async (resource, options) => {
    calls.push({resource, options});
    const response = await respond?.(resource, options, calls);
    return response || {ok: true, json: async () => defaults[resource] ?? []};
  };
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    const source = cache.get(key) ?? fs.readFileSync(path.join(root, resource.pathname), 'utf8');
    vm.runInContext(source, context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const open = () => w.document.querySelector('[data-view="finance"]').click();
  const submit = async () => { $('finance-submit').click(); await flush(); };
  return {w, $, calls, open, submit};
}

function assertResult(ui) {
  assert.equal(ui.$('finance-results').hidden, false);
  assert.match(ui.$('finance-candidates').textContent, /测试存款方向/);
  assert.equal(ui.$('finance-submit').disabled, false);
  const writes = ui.calls.filter(call => call.options.method === 'POST');
  assert.deepEqual(writes.map(call => call.resource), ['/api/finance/recommendations']);
  assert.equal(writes[0].options.headers['X-CSRF-Token'], session.csrf);
  assert.equal(JSON.parse(writes[0].options.body).amountMinor, 1000000);
}

test('all page scripts and styles use their current content digest to avoid stale mixed releases', () => {
  const dom = new JSDOM(html);
  try {
    for (const element of dom.window.document.querySelectorAll('script[src], link[rel="stylesheet"]')) {
      const resource = new URL(element.getAttribute('src') || element.getAttribute('href'), 'https://safebank.invalid');
      const digest = createHash('sha256').update(fs.readFileSync(path.join(root, resource.pathname))).digest('hex').slice(0, 12);
      assert.equal(resource.searchParams.get('v'), digest, resource.pathname + ' must invalidate older cached content');
    }
  } finally { dom.window.close(); }
});

test('navigation and generation work when the old unversioned application is in the browser cache', async t => {
  // A pre-finance cached bundle has no finance navigation handler.
  const cache = new Map([['/app.js', '/* cached application from before finance navigation existed */']]);
  const ui = boot(t, {cache});
  await flush();
  ui.open();
  assert.equal(ui.$('view-finance').hidden, false);
  assert.equal(ui.$('view-title').textContent, '金融推荐');
  await ui.submit();
  assertResult(ui);
  ui.$('finance-to-savings').click();
  assert.equal(ui.$('view-savings').hidden, false);
});

test('direct finance links select the page and generate using the established session', async t => {
  const ui = boot(t, {url: 'https://safebank.invalid/?view=finance'});
  await flush();
  assert.equal(ui.$('view-finance').hidden, false);
  await ui.submit();
  assertResult(ui);
});

for (const failed of ['/api/audit', '/api/users/profile', '/api/actions']) {
  test('finance remains usable when unrelated startup data fails: ' + failed, async t => {
    const ui = boot(t, {respond: async resource => resource === failed ? {ok: false, json: async () => ({message: '测试初始化失败'})} : undefined});
    await flush();
    ui.open();
    assert.equal(ui.$('finance-submit').disabled, false, 'unrelated startup failure must not lock recommendations');
    await ui.submit();
    assertResult(ui);
  });
}

test('generation can recover after the initial session request fails without reloading', async t => {
  let attempts = 0;
  const ui = boot(t, {respond: async resource => {
    if (resource === '/api/session' && ++attempts === 1) throw Error('测试网络中断');
  }});
  await flush();
  ui.open();
  await ui.submit();
  assertResult(ui);
  assert.equal(attempts, 2);
});

test('generation waits for the pending session and does not wait for unrelated initialization', async t => {
  let resolveSession;
  const pending = new Promise(resolve => { resolveSession = resolve; });
  const ui = boot(t, {respond: async resource => {
    if (resource === '/api/session') return pending;
    if (resource === '/api/audit') return new Promise(() => {});
  }});
  ui.open();
  await ui.submit();
  assert.equal(ui.calls.filter(call => call.resource === '/api/session').length, 1);
  assert.equal(ui.calls.some(call => call.options.method === 'POST'), false);
  resolveSession({ok: true, json: async () => session});
  await flush();
  assertResult(ui);
});
