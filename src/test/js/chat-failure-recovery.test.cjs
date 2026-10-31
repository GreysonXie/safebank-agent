const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {JSDOM} = require('jsdom');

const root = path.resolve(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const session = {csrf: 'synthetic-csrf', user: 'synthetic-user', mode: 'LANGGRAPH_MODEL', faultsEnabled: false, businessDate: '2026-10-31'};

/** 真实模型路径的失败：本轮已确定失败、没有银行写操作，必须能直接再问，不能被"上一句未完成"拦住。 */
function boot(t, {chat}) {
  const dom = new JSDOM(html, {url: 'https://safebank.invalid/', runScripts: 'outside-only'});
  t.after(() => dom.window.close());
  const w = dom.window, context = dom.getInternalVMContext(), calls = [];
  w.Element.prototype.scrollIntoView = () => {};
  const defaults = {
    '/api/session': session,
    '/api/users/profile': {name: '测试用户', phone: '', gender: 'UNSPECIFIED'},
    '/api/models/providers': [{id: 'local', name: '测试', icon: 'custom', baseUrl: 'https://model.invalid', models: ['demo']}],
    '/api/models/configuration': {provider: 'local', baseUrl: 'https://model.invalid', model: 'demo', enabled: true, hasApiKey: true},
    '/api/conversations': {conversationId: 'conversation-1', version: 1, state: 'ACTIVE'}
  };
  w.fetch = async (resource, options = {}) => {
    calls.push({resource, options});
    if (resource === '/api/chat') return chat(options);
    return {ok: true, json: async () => defaults[resource] ?? []};
  };
  for (const script of w.document.querySelectorAll('script[src]')) {
    const resource = new URL(script.src), key = resource.pathname + resource.search;
    vm.runInContext(fs.readFileSync(path.join(root, resource.pathname), 'utf8'), context, {filename: key});
  }
  const $ = id => w.document.getElementById(id);
  const settle = async () => { for (let i = 0; i < 6; i++) await flush(); };
  const ask = async text => {
    $('prompt').value = text;
    $('chat-form').requestSubmit($('chat-form').querySelector('button[type="submit"]'));
    await settle();
  };
  return {w, $, calls, ask, settle};
}

const failure = (code, message) => ({
  ok: false,
  json: async () => ({code, message})
});
const success = text => ({
  ok: true,
  json: async () => ({mode: 'LANGGRAPH_MODEL', text, data: {}, actions: [], trace: [], results: []})
});

test('a definitive model failure ends the turn and the next question is not blocked', async t => {
  let attempt = 0;
  const ui = boot(t, {chat: () => (++attempt === 1
    ? failure('MODEL_EFFECT_UNKNOWN', '本次没有拿到模型响应，未执行任何银行操作')
    : success('这是第二次提问的回答'))});
  ui.w.localStorage.setItem('safebank.pendingChat', JSON.stringify({text: '帮我看上个月吃饭花了多少', requestId: 'request-1', conversationId: 'conversation-1', expectedContextVersion: 1}));
  await ui.settle();
  await ui.ask('帮我看上个月吃饭花了多少');
  assert.match(ui.$('chat-status').textContent, /未执行任何银行操作/);
  assert.match(ui.$('chat-status').textContent, /换一种问法再问|再问/);
  assert.equal(ui.$('chat-turn-close').hidden, true, '确定失败不需要"结束本次处理"');
  assert.equal(ui.$('prompt').value, '帮我看上个月吃饭花了多少', '原问题留在输入框，便于修改后重问');
  await ui.ask('帮我看上个月吃饭花了多少');
  const chats = ui.calls.filter(call => call.resource === '/api/chat');
  assert.equal(chats.length, 2, '第二次提问必须真的发出，不能被"上次请求未完成"拦住');
  assert.match(ui.$('messages').textContent, /这是第二次提问的回答/);
});

test('a paused turn offers the safe close instead of silently blocking further questions', async t => {
  const ui = boot(t, {chat: () => failure('AGENT_REVIEW_REQUIRED', '原轮次已暂停，尚无可核验的完整结果')});
  ui.w.localStorage.setItem('safebank.pendingChat', JSON.stringify({text: '帮我看上个月吃饭花了多少', requestId: 'request-1', conversationId: 'conversation-1', expectedContextVersion: 1}));
  await ui.settle();
  await ui.ask('帮我看上个月吃饭花了多少');
  assert.equal(ui.$('chat-turn-close').hidden, false, '暂停时要给出安全出口');
  assert.match(ui.$('chat-turn-close-note').textContent, /原轮次已暂停/);
});
