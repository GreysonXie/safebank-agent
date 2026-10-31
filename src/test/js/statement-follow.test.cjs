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

const bucket = (key, expense, income, count) => ({
  key, label: key, start: key === '2026-08' ? '2026-08-01' : '2026-09-01', end: key === '2026-08' ? '2026-08-31' : '2026-09-30',
  metrics: {expense_minor: expense, income_minor: income, net_minor: income - expense, internal_transfer_minor: 0, transaction_count: count}
});
const query = (start, end, bucketName) => ({
  start, end, bucket: bucketName, group_by: 'category',
  filter: {direction: '全部', categories: [], merchants: [], accounts: [], min_amount_minor: null, max_amount_minor: null, exclude_internal: false},
  compare: 'none', top_n: 10
});
const rangeEnvelope = {
  query: query('2026-08-01', '2026-10-29', 'month'),
  totals: {expense_minor: 90000, income_minor: 500000, net_minor: 410000, internal_transfer_minor: 0, transaction_count: 9},
  buckets: [bucket('2026-08', 40000, 0, 4), bucket('2026-09', 50000, 500000, 5)],
  breakdown: {dimension: 'category', measure: 'expense', groups: [
    {key: '餐饮', label: '餐饮', metrics: {expense_minor: 90000, income_minor: 0, net_minor: -90000, internal_transfer_minor: 0, transaction_count: 9}, amount_minor: 90000, share_percent: 100}
  ], truncated: false, rest_amount_minor: 0, rest_count: 0},
  comparison: null, anomaly_candidates: [],
  rows: {total_count: 9, offset: 0, limit: 20, truncated: false, items: []},
  coverage: {business_date: '2026-10-30', first_booked_on: '2026-08-03', last_booked_on: '2026-09-30', beyond_business_date: false},
  notes: ['统计区间 2026-08-01 至 2026-10-29（含首尾两天），粒度 month', '内部转账不计入收入支出；本人账户之间互转单列为内部转账']
};
const narrowedEnvelope = {
  query: query('2026-08-01', '2026-08-31', 'none'),
  totals: {expense_minor: 40000, income_minor: 0, net_minor: -40000, internal_transfer_minor: 0, transaction_count: 4},
  buckets: [{key: '总', label: '总', start: '2026-08-01', end: '2026-08-31', metrics: {expense_minor: 40000, income_minor: 0, net_minor: -40000, internal_transfer_minor: 0, transaction_count: 4}}],
  breakdown: {dimension: 'category', measure: 'expense', groups: [{key: '餐饮', label: '餐饮', metrics: {expense_minor: 40000, income_minor: 0, net_minor: -40000, internal_transfer_minor: 0, transaction_count: 4}, amount_minor: 40000, share_percent: 100}], truncated: false, rest_amount_minor: 0, rest_count: 0},
  comparison: null, anomaly_candidates: [], rows: {total_count: 4, offset: 0, limit: 20, truncated: false, items: []},
  coverage: {business_date: '2026-10-30', first_booked_on: '2026-08-03', last_booked_on: '2026-08-31', beyond_business_date: false},
  notes: ['统计区间 2026-08-01 至 2026-08-31（含首尾两天），粒度 none']
};

function boot(t, follow) {
  const dom = new JSDOM(html, {url: 'https://safebank.invalid/', runScripts: 'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, context = dom.getInternalVMContext(), calls = [], posts = [];
  w.Element.prototype.scrollIntoView = () => {};
  const view = {conversationId: 'c1', version: 1, intent: 'STATEMENT', state: 'ACTIVE', slots: {}, proposal: null, displayHistory: [], bankReferences: []};
  const defaults = {
    '/api/session': session,
    '/api/users/profile': {name: '测试用户', phone: '', gender: 'UNSPECIFIED'},
    '/api/models/providers': [{id: 'local', name: '测试', icon: 'custom', baseUrl: 'https://model.invalid', models: ['demo']}],
    '/api/models/configuration': {provider: 'local', baseUrl: 'https://model.invalid', model: 'demo', enabled: false, hasApiKey: false},
    '/api/statement/months': ['2026-10'],
    '/api/statement/years': ['2026'],
    '/api/conversations/c1': view,
    '/api/conversations/c1/statement-query': follow
  };
  w.fetch = async (resource, init) => {
    calls.push(resource);
    if (init && init.body) posts.push({url: resource, body: JSON.parse(init.body)});
    if (resource === '/api/statement/query') return {ok: true, json: async () => narrowedEnvelope};
    return {ok: true, json: async () => defaults[resource] ?? []};
  };
  // 已有会话：让页面走真实恢复路径，从而触发侧栏跟随（同一用户的会话不会被初始化清空）。
  w.sessionStorage.setItem('safebank.contextUser', JSON.stringify(session.user));
  w.sessionStorage.setItem('safebank.context', JSON.stringify({conversationId: 'c1', version: 1}));
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    vm.runInContext(fs.readFileSync(path.join(root, resource.pathname), 'utf8'), context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const settle = async () => { for (let i = 0; i < 6; i++) await flush(); };
  return {w, $, calls, posts, settle};
}

test('conversation range query fills the side panel with its exact scope', async t => {
  const ui = boot(t, {found: true, query: rangeEnvelope.query, result: rangeEnvelope, observedAt: '2026-10-30T02:00:00Z'});
  await ui.settle();
  assert.equal(ui.$('statement-follow').hidden, false, '有区间查询后显示对话口径面板');
  assert.match(ui.$('statement-follow-query').textContent, /2026-08-01 至 2026-10-29/);
  assert.match(ui.$('statement-follow-query').textContent, /按月/);
  assert.match(ui.$('statement-follow-query').textContent, /按分类/);
  assert.match(ui.$('statement-follow-query').textContent, /回执快照/);
  assert.equal(ui.$('statement-follow-metrics').children.length, 5);
  assert.equal(ui.$('statement-follow-buckets').children.length, 2, '两桶各一行');
  const first = ui.$('statement-follow-buckets').children[0].textContent;
  assert.match(first, /2026-08/);
  assert.match(first, /400\.00/);
  assert.match(ui.$('statement-follow-groups').textContent, /餐饮/);
  assert.match(ui.$('statement-follow-note').textContent, /内部转账不计入收入支出/);
  assert.equal(ui.$('statement-follow-back').hidden, true, '未下钻时不显示返回');
});

test('without a range query the default monthly and annual panels stay untouched', async t => {
  const ui = boot(t, {found: false});
  await ui.settle();
  assert.equal(ui.$('statement-follow').hidden, true);
  assert.equal(ui.$('statement-follow-buckets').children.length, 0);
  assert.equal(ui.calls.filter(call => call === '/api/statement/query').length, 0, '没有回执时不发起任何新查询');
  assert.match(ui.$('statement-year-status').textContent + ui.$('statement-status').textContent, /./);
});

test('a bucket opens the same query narrowed, and the panel can return to the conversation scope', async t => {
  const ui = boot(t, {found: true, query: rangeEnvelope.query, result: rangeEnvelope, observedAt: null});
  await ui.settle();
  const button = ui.$('statement-follow-buckets').children[0].querySelector('button');
  button.dispatchEvent(new ui.w.Event('click'));
  await ui.settle();
  assert.equal(ui.posts.length, 1, '下钻复用同一个查询接口');
  assert.equal(ui.posts[0].url, '/api/statement/query');
  assert.equal(ui.posts[0].body.start, '2026-08-01');
  assert.equal(ui.posts[0].body.end, '2026-08-31');
  assert.equal(ui.posts[0].body.bucket, 'none');
  assert.equal(ui.posts[0].body.groupBy, 'category');
  assert.match(ui.$('statement-follow-query').textContent, /2026-08-01 至 2026-08-31/);
  assert.equal(ui.$('statement-follow-back').hidden, false);
  ui.$('statement-follow-back').dispatchEvent(new ui.w.Event('click'));
  await ui.settle();
  assert.match(ui.$('statement-follow-query').textContent, /2026-08-01 至 2026-10-29/);
  assert.equal(ui.$('statement-follow-back').hidden, true);
  assert.equal(ui.posts.length, 1, '返回原口径用回执快照，不额外查询');
});

test('the panel can be dismissed without losing the monthly default', async t => {
  const ui = boot(t, {found: true, query: rangeEnvelope.query, result: rangeEnvelope, observedAt: null});
  await ui.settle();
  ui.$('statement-follow-close').dispatchEvent(new ui.w.Event('click'));
  assert.equal(ui.$('statement-follow').hidden, true);
  assert.match(ui.$('statement-month').options[0].textContent, /请选择月份/);
});
