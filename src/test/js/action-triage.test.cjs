const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {JSDOM} = require('jsdom');

const root = path.resolve(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const session = {csrf: 'synthetic-csrf', user: 'synthetic-user', mode: 'OFFLINE_DEMO', faultsEnabled: false, businessDate: '2026-10-31'};

function action(overrides) {
  return {
    id: 'action-1', user_id: 'u1', kind: 'TRANSFER', payload_hash: 'hash-1', status: 'AWAITING_AUTH',
    expires_at: '2026-10-31T06:00:00Z', receipt_id: null, error_code: null,
    source_id: 'checking', recipient_id: 'zhang-a', recipient_label: '张三 · 同学', amount_minor: 50000,
    currency: 'CNY', fee_minor: 0,
    security: {riskLevel: 'YELLOW', decisionId: 'decision-1', projectedMinor: 50000, needsRefresh: false, verificationMode: 'LOCAL_DEMO_ONLY'},
    ...overrides
  };
}

function boot(t, {actions}) {
  const dom = new JSDOM(html, {url: 'https://safebank.invalid/', runScripts: 'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, context = dom.getInternalVMContext();
  const defaults = {
    '/api/session': session,
    '/api/users/profile': {name: '测试用户', phone: '', gender: 'UNSPECIFIED'},
    '/api/models/providers': [{id: 'local', name: '测试', icon: 'custom', baseUrl: 'https://model.invalid', models: ['demo']}],
    '/api/models/configuration': {provider: 'local', baseUrl: 'https://model.invalid', model: 'demo', enabled: false, hasApiKey: false},
    '/api/actions': actions,
    '/api/statement/months': ['2026-10']
  };
  w.fetch = async resource => ({ok: true, json: async () => defaults[resource] ?? []});
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    vm.runInContext(fs.readFileSync(path.join(root, resource.pathname), 'utf8'), context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const settle = async () => { await flush(); await flush(); };
  return {w, $, settle};
}

test('expired previews are separated from pending work and cannot be confirmed', async t => {
  const ui = boot(t, {actions: [action({expires_at: '2026-10-01T00:00:00Z'})]});
  await ui.settle();
  assert.equal(ui.$('expired-actions').children.length, 1, '过期预览进入已过期分区');
  assert.equal(ui.$('actions').querySelector('.action-row'), null, '过期预览不混入待处理');
  assert.equal(ui.$('pending-count').textContent, '0');
  assert.equal(ui.$('expired-count').textContent, '1');
  const row = ui.$('expired-actions').querySelector('.action-row');
  assert.equal(row.dataset.expired, 'true');
  assert.match(row.querySelector('.status').textContent, /过期/);
  row.querySelector('button').click();
  const card = ui.$('action-dialog-body').querySelector('.action');
  assert.ok(card, '过期预览在弹窗里只读展示');
  assert.equal(card.dataset.expired, 'true');
  assert.equal(card.querySelector('.verification'), null, '过期后不再提供勾选确认');
  assert.equal(card.querySelector('button.primary'), null, '过期后不再提供确认执行按钮');
  assert.match(card.querySelector('.action-next-step').textContent, /过期/);
  assert.match(ui.$('action-dialog-note').textContent, /已过期/);
});

test('a live preview stays in the pending bucket with its explicit confirmation', async t => {
  const ui = boot(t, {actions: [action({expires_at: '2099-01-01T00:00:00Z'})]});
  await ui.settle();
  assert.equal(ui.$('actions').querySelectorAll('.action-row').length, 1);
  assert.equal(ui.$('expired-actions').children.length, 0);
  assert.equal(ui.$('pending-count').textContent, '1');
  const row = ui.$('actions').querySelector('.action-row');
  assert.equal(row.dataset.expired, undefined);
  row.querySelector('button').click();
  const card = ui.$('action-dialog-body').querySelector('.action');
  assert.ok(card.querySelector('.verification input'), '保留原有勾选确认');
  assert.ok(card.querySelector('button.primary'), '保留确认执行按钮');
});

test('the review desk is its own navigation entry instead of being embedded in every business view', async t => {
  const ui = boot(t, {actions: []});
  await ui.settle();
  const nav = ui.w.document.querySelector('[data-view="actions"]');
  assert.ok(nav, '确认台有独立入口');
  const center = ui.$('action-center'), view = ui.$('view-actions');
  assert.ok(view && view.contains(center), '确认台在自己的视图里');
  for (const other of ['view-statement', 'view-chat', 'view-mandates', 'view-savings', 'view-tools', 'view-audit']) {
    assert.equal(ui.$(other).contains(center), false, '业务页不再内嵌确认台：' + other);
  }
  nav.click();
  assert.equal(view.hidden, false);
  assert.equal(ui.$('view-statement').hidden, true);
  assert.equal(ui.$('view-title').textContent, '操作确认台');
});

test('the navigation badge surfaces pending work while other views are open', async t => {
  const ui = boot(t, {actions: [action({expires_at: '2099-01-01T00:00:00Z'})]});
  await ui.settle();
  const badge = ui.$('nav-pending-count');
  assert.equal(badge.hidden, false);
  assert.equal(badge.textContent, '1');
});

test('the expired tab lists only expired previews and counts stay exact', async t => {
  const ui = boot(t, {actions: [action({id: 'old', expires_at: '2026-10-01T00:00:00Z'}), action({id: 'live', expires_at: '2099-01-01T00:00:00Z'})]});
  await ui.settle();
  assert.equal(ui.$('expired-count').textContent, '1');
  assert.equal(ui.$('pending-count').textContent, '1');
  ui.w.document.querySelector('[data-action-tab="expired"]').click();
  assert.equal(ui.$('actions').hidden, true);
  assert.equal(ui.$('action-expired').hidden, false);
  assert.match(ui.$('action-center-help').textContent, /重新生成/);
  ui.w.document.querySelector('[data-action-tab="pending"]').click();
  assert.equal(ui.$('actions').hidden, false);
  assert.equal(ui.$('action-expired').hidden, true);
});

test('a long expired backlog stays readable: capped list, honest count', async t => {
  const actions = Array.from({length: 20}, (_, index) => action({id: 'old-' + index, expires_at: '2026-10-01T00:00:00Z'}));
  const ui = boot(t, {actions});
  await ui.settle();
  ui.w.document.querySelector('[data-action-tab="expired"]').click();
  const rows = [...ui.$('expired-actions').querySelectorAll('.action-row')];
  assert.equal(rows.length, 20);
  assert.equal(ui.$('expired-count').textContent, '20', '计数保持真实');
  assert.equal(rows.filter(row => !row.hidden).length, 12, '只渲染最近 12 条');
  assert.match(ui.$('action-expired-summary').textContent, /仅显示最近 12 条/);
});

test('the confirmation modal is the only place a preview is confirmed', async t => {
  const ui = boot(t, {actions: [action({expires_at: '2099-01-01T00:00:00Z'})]});
  await ui.settle();
  assert.equal(ui.$('action-dialog-body').children.length, 0, '未打开弹窗前不在页面里渲染完整确认卡');
  ui.w.document.querySelector('[data-view="actions"]').click();
  ui.$('actions').querySelector('.action-row button').click();
  const dialog = ui.$('action-dialog');
  assert.equal(dialog.hasAttribute('open'), true, '点击处理后以弹窗打开');
  assert.equal(ui.$('view-actions').hidden, false, '弹窗在确认台页面内');
  assert.match(ui.$('action-dialog-title').textContent, /转账操作/);
  assert.ok(ui.$('action-dialog-body').querySelector('.action'));
  ui.$('action-dialog-close').click();
  assert.equal(ui.$('action-dialog-body').children.length, 0, '关闭弹窗后不保留旧卡片');
});
