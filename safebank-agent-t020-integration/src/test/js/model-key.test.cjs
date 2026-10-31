const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {JSDOM} = require('jsdom');

const root = path.resolve(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const FULL_KEY = 'sk-synthetic-full-secret-value-t8GV';
const session = {csrf: 'synthetic-csrf', user: 'synthetic-user', mode: 'LANGGRAPH_MODEL', faultsEnabled: false, businessDate: '2026-10-31'};

function boot(t, {config} = {}) {
  const dom = new JSDOM(html, {url: 'https://safebank.invalid/', runScripts: 'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, context = dom.getInternalVMContext(), calls = [];
  w.Element.prototype.scrollIntoView = () => {};
  const defaults = {
    '/api/session': session,
    '/api/users/profile': {name: '测试用户', phone: '', gender: 'UNSPECIFIED'},
    '/api/models/providers': [{id: 'deepseek', name: 'DeepSeek', icon: 'deepseek', baseUrl: 'https://api.deepseek.com', models: ['deepseek-flash']}],
    '/api/models/configuration': config,
    '/api/models/test': {valid: true, model: 'deepseek-flash', baseUrl: 'https://api.deepseek.com', host: 'api.deepseek.com',
      keySource: 'saved', keyHint: '…t8GV', latencyMs: 812, message: '连接检测通过，可保存并应用。'}
  };
  w.fetch = async (resource, options = {}) => {
    calls.push({resource, options});
    return {ok: true, json: async () => defaults[resource] ?? []};
  };
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    vm.runInContext(fs.readFileSync(path.join(root, resource.pathname), 'utf8'), context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const settle = async () => { for (let i = 0; i < 6; i++) await flush(); };
  return {w, $, calls, settle};
}

const savedConfig = {
  provider: 'deepseek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash', hasApiKey: true,
  apiKeyHint: '…t8GV', enabled: true, verified: false, verifiedAt: null, revision: 'revision-1', source: 'local'
};

test('the saved key is never written back into the page; only the last four characters are shown', async t => {
  const ui = boot(t, {config: savedConfig});
  await ui.settle();
  const input = ui.$('model-api-key');
  assert.equal(input.value, '', '密钥不会回填到输入框');
  assert.equal(input.type, 'password');
  assert.match(ui.$('model-key-note').textContent, /…t8GV/);
  assert.match(ui.$('model-key-note').textContent, /留空即沿用/);
  assert.equal(ui.w.document.body.innerHTML.includes(FULL_KEY), false, '页面里不出现完整密钥');
});

test('changing the endpoint host asks for the key again instead of silently reusing it', async t => {
  const ui = boot(t, {config: savedConfig});
  await ui.settle();
  ui.$('model-base-url').value = 'https://other.example.com/v1';
  ui.$('model-base-url').dispatchEvent(new ui.w.Event('input', {bubbles: true}));
  assert.match(ui.$('model-key-note').textContent, /需要重新填写/);
});

test('the connectivity test tells the customer the saved key was reused', async t => {
  const ui = boot(t, {config: savedConfig});
  await ui.settle();
  ui.$('model-test').click();
  await ui.settle();
  const text = ui.$('model-test-result').textContent;
  assert.match(text, /检测通过/);
  assert.match(text, /使用已保存密钥/);
  assert.match(text, /…t8GV/);
  assert.match(text, /812/);
});
