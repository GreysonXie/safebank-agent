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
  month, expense_minor: 100000, income_minor: 0, internal_transfer_minor: 0,
  categories: {餐饮: 100000}, transactions: [], anomaly_candidates: [], anomaly_rule: '合成规则', usage_data_available: false
});
const emptyYear = year => ({
  year, expense_minor: 0, income_minor: 0, internal_transfer_minor: 0, categories: {}, record_count: 0,
  months: Array.from({length: 12}, (_, index) => ({month: year + '-' + String(index + 1).padStart(2, '0'), expense_minor: 0, income_minor: 0, internal_transfer_minor: 0, transaction_count: 0})),
  coverage: {business_date: '2026-10-31', year_in_progress: false, first_booked_on: null, last_booked_on: null, beyond_business_date: false}
});
const yearWithData = {
  year: '2026', expense_minor: 617733, income_minor: 1170000, internal_transfer_minor: 100000,
  categories: {餐饮: 20000}, record_count: 42,
  months: Array.from({length: 12}, (_, index) => {
    const month = '2026-' + String(index + 1).padStart(2, '0');
    const count = index === 8 ? 12 : 0;
    return {month, expense_minor: count ? 1000 : 0, income_minor: 0, internal_transfer_minor: 0, transaction_count: count};
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
    if (resource.startsWith('/api/statement/year?')) {
      const year = new URLSearchParams(resource.split('?')[1]).get('year');
      return {ok: true, json: async () => (year === '2026' ? yearWithData : emptyYear(year))};
    }
    if (resource.startsWith('/api/statement?month=')) return {ok: true, json: async () => statementFor(new URLSearchParams(resource.split('?')[1]).get('month'))};
    return {ok: true, json: async () => defaults[resource] ?? []};
  };
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    vm.runInContext(fs.readFileSync(path.join(root, resource.pathname), 'utf8'), context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const settle = async () => { for (let i = 0; i < 5; i++) await flush(); };
  return {w, $, calls, settle};
}

test('the monthly bill is not loaded until the customer picks a month', async t => {
  const ui = boot(t);
  await ui.settle();
  const monthlyCalls = ui.calls.filter(call => call.startsWith('/api/statement?month='));
  assert.deepEqual(monthlyCalls, [], '打开页面不自动读取任何月份');
  assert.equal(ui.$('statement-month').value, '');
  assert.match(ui.$('statement-month').options[0].textContent, /请选择月份/);
  assert.match(ui.$('statement-status').textContent, /请选择要查看的月份/);
  assert.equal(ui.$('statement-metrics').children.length, 0, '未选择前不显示月度数字');
  assert.match(ui.$('transaction-rows').textContent, /请先选择要查看的月份/);
  assert.equal(ui.$('month-prev').disabled, true);
  assert.equal(ui.$('month-next').disabled, true);
});

test('choosing a month loads exactly that month and shows its figures', async t => {
  const ui = boot(t);
  await ui.settle();
  ui.$('statement-month').value = '2026-09';
  ui.$('statement-month').dispatchEvent(new ui.w.Event('change', {bubbles: true}));
  await ui.settle();
  const monthlyCalls = ui.calls.filter(call => call.startsWith('/api/statement?month='));
  assert.equal(monthlyCalls[0], '/api/statement?month=2026-09', '先读取客户选中的月份');
  assert.ok(monthlyCalls.length >= 1);
  assert.match(ui.$('statement-status').textContent, /2026-09/);
  assert.equal(ui.$('statement-metrics').children.length, 4);
});

test('the year selector offers past years even when only the current year has records', async t => {
  const ui = boot(t);
  await ui.settle();
  const labels = [...ui.$('statement-year').options].map(option => option.textContent);
  assert.ok(labels.some(label => /2025 年/.test(label)), '过去年份可选：' + labels.join(' / '));
  assert.ok(labels.some(label => /2024 年/.test(label)), '至少给出最近五年');
  assert.ok(labels.some(label => /2026 年/.test(label)));
  assert.ok(labels.some(label => /2025 年（无记录）/.test(label)), '没有数据的年份要标注，不能让人以为有数据');
  assert.equal(ui.$('statement-year').value, '2026', '默认仍停留在有数据的当前年');
});

test('choosing a year without records states it honestly instead of inventing numbers', async t => {
  const ui = boot(t);
  await ui.settle();
  ui.$('statement-year').value = '2025';
  ui.$('statement-year').dispatchEvent(new ui.w.Event('change', {bubbles: true}));
  await ui.settle();
  assert.ok(ui.calls.includes('/api/statement/year?year=2025'));
  assert.match(ui.$('statement-year-status').textContent, /没有查询到交易记录/);
  assert.match(ui.$('statement-year-note').textContent, /没有查询到交易记录/);
  const rows = [...ui.$('statement-year-rows').querySelectorAll('tr')];
  assert.equal(rows.length, 12, '12 个月仍然完整列出');
  assert.equal(rows.every(row => row.querySelector('button').disabled), true, '没有记录的月份不提供明细入口');
});
