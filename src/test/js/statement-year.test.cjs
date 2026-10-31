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

const months = ['2026-10', '2026-09', '2026-08'];
const statementFor = month => ({
  month, expense_minor: month === '2026-10' ? 80800 : 536933, income_minor: 0, internal_transfer_minor: 100000,
  categories: month === '2026-10' ? {} : {餐饮: 20000}, transactions: [], anomaly_candidates: [], anomaly_rule: '合成规则', usage_data_available: false
});
const year = {
  year: '2026', expense_minor: 617733, income_minor: 1170000, internal_transfer_minor: 100000,
  categories: {餐饮: 20000, 购物: 152333}, record_count: 42,
  months: Array.from({length: 12}, (_, index) => {
    const month = '2026-' + String(index + 1).padStart(2, '0');
    const count = index === 8 ? 12 : index === 9 ? 3 : 0;
    return {month, expense_minor: count ? count * 1000 : 0, income_minor: 0, internal_transfer_minor: 0, transaction_count: count};
  }),
  coverage: {business_date: '2026-10-31', year_in_progress: true, first_booked_on: '2026-01-05', last_booked_on: '2026-10-20', beyond_business_date: false}
};

function boot(t) {
  const dom = new JSDOM(html, {url: 'https://safebank.invalid/', runScripts: 'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, context = dom.getInternalVMContext(), calls = [];
  w.Element.prototype.scrollIntoView = () => {};
  const defaults = {
    '/api/session': session,
    '/api/users/profile': {name: '测试用户', phone: '', gender: 'UNSPECIFIED'},
    '/api/models/providers': [{id: 'local', name: '测试', icon: 'custom', baseUrl: 'https://model.invalid', models: ['demo']}],
    '/api/models/configuration': {provider: 'local', baseUrl: 'https://model.invalid', model: 'demo', enabled: false, hasApiKey: false},
    '/api/statement/months': months,
    '/api/statement/years': ['2026']
  };
  w.fetch = async resource => {
    calls.push(resource);
    if (resource.startsWith('/api/statement/year?')) return {ok: true, json: async () => year};
    if (resource.startsWith('/api/statement?month=')) return {ok: true, json: async () => statementFor(new URLSearchParams(resource.split('?')[1]).get('month'))};
    return {ok: true, json: async () => defaults[resource] ?? []};
  };
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    vm.runInContext(fs.readFileSync(path.join(root, resource.pathname), 'utf8'), context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const settle = async () => { for (let i = 0; i < 4; i++) await flush(); };
  return {w, $, calls, settle};
}

test('the annual panel summarises the year, keeps every month visible and states its coverage', async t => {
  const ui = boot(t);
  await ui.settle();
  assert.equal(ui.$('statement-year').value, '2026', '默认年份跟随真实业务年份');
  assert.match(ui.$('statement-year-status').textContent, /42 笔交易/);
  assert.match(ui.$('statement-year-status').textContent, /本年进行中/);
  const rows = [...ui.$('statement-year-rows').querySelectorAll('tr')];
  assert.equal(rows.length, 12, '12 个月全部列出，没有记录的月份不隐藏');
  assert.equal(rows[0].dataset.month, '2026-01');
  assert.equal(rows[11].dataset.month, '2026-12');
  assert.match(ui.$('statement-year-metrics').textContent, /年度支出/);
  assert.match(ui.$('statement-year-metrics').textContent, /¥6,177\.33/);
  assert.match(ui.$('statement-year-note').textContent, /2026-01-05 至 2026-10-20/);
  assert.match(ui.$('statement-year-note').textContent, /业务日期 2026-10-31/);
  assert.match(ui.$('statement-year-note').textContent, /内部转账不计收支/);
  assert.match(ui.$('statement-year-categories').textContent, /购物/);
});

test('a month without records cannot be opened, and a month with records drills down', async t => {
  const ui = boot(t);
  await ui.settle();
  const rows = [...ui.$('statement-year-rows').querySelectorAll('tr')];
  const empty = rows[0].querySelector('button'), filled = rows[8].querySelector('button');
  assert.equal(empty.disabled, true, '没有记录的月份不提供明细入口');
  assert.equal(empty.textContent, '无记录');
  assert.equal(filled.disabled, false);
  filled.click();
  await ui.settle();
  assert.equal(ui.$('statement-month').value, '2026-09');
  assert.equal(ui.$('view-statement').hidden, false);
  assert.ok(ui.calls.includes('/api/statement?month=2026-09'), '点击后按该月读取单月账单');
  assert.match(ui.$('statement-status').textContent, /2026-09/);
});
