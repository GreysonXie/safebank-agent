const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

const APP=path.resolve(__dirname,'../../main/resources/static/app.js');
const source=fs.readFileSync(APP,'utf8');

function sliceFn(name){
 const marker=source.indexOf('function '+name+'(');const start=source.slice(Math.max(0,marker-6),marker)==='async '?marker-6:marker;
 assert.ok(start>=0,'未找到函数 '+name);
 const end=source.indexOf('\n}',start);
 assert.ok(end>start,'函数边界解析失败 '+name);
 return source.slice(start,end+2);
}

// 状态与常量：从 actionCards 声明到 updateActionSummary 之前
function stateBlock(){
 const start=source.indexOf('const actionCards=new Map()');
 const end=source.indexOf('function updateActionSummary');
 assert.ok(start>=0&&end>start,'状态块解析失败');
 return source.slice(start,end);
}

// 任务记录分流块：renderRecord/recordSummary 现在依赖它
function triageBlock(){
 const start=source.indexOf('// --- record-triage:begin');
 const end=source.indexOf('// --- record-triage:end ---');
 assert.ok(start>=0&&end>start,'record-triage 块解析失败');
 return source.slice(start,end);
}

function el(tag,text,cls){ const node={tag,text:text===undefined||text===null?'':String(text),className:cls||'',children:[],parentNode:null,
  dataset:{},style:{},classList:{add(){},remove(){},toggle(){},contains(){return false}},
  append(...items){for(const i of items){if(i){if(i.parentNode){const at=i.parentNode.children.indexOf(i);if(at>=0)i.parentNode.children.splice(at,1);}i.parentNode=node;node.children.push(i);}}},
  prepend(...items){for(const i of items.slice().reverse()){if(i){if(i.parentNode){const at=i.parentNode.children.indexOf(i);if(at>=0)i.parentNode.children.splice(at,1);}i.parentNode=node;node.children.unshift(i);}}},
  replaceChildren(...items){node.children=items;for(const i of items)if(i)i.parentNode=node;},
  remove(){if(node.parentNode){const at=node.parentNode.children.indexOf(node);if(at>=0)node.parentNode.children.splice(at,1);node.parentNode=null;}},
  querySelector(sel){return find(node,sel);},
  querySelectorAll(sel){const out=[];walk(node,n=>{if(match(n,sel))out.push(n);});return out;},
  setAttribute(){},removeAttribute(){},addEventListener(){},focus(){},scrollIntoView(){},
  showModal(){node.open=true;},close(){node.open=false;},get open(){return !!node._open;},set open(v){node._open=v;},
  get firstChild(){return node.children[0]||null;}};
 return node;
}

function walk(node,fn){for(const child of node.children||[]){(fn(child),walk(child,fn));}}
function match(node,sel){
 if(sel.startsWith('[data-action-id]'))return node.dataset&&node.dataset.actionId!==undefined;
 if(sel==='[data-summary-for]')return node.dataset&&node.dataset.summaryFor!==undefined;
 if(sel==='[data-record-id]')return node.dataset&&node.dataset.recordId!==undefined;
 if(sel.indexOf('[data-record-id=')===0){const want=sel.slice(15).replace(/["\]]/g,'');return node.dataset&&node.dataset.recordId===want;}if(sel.indexOf('[data-summary-for=')===0){const want=sel.split('"').length>1?sel.split('"')[1]:sel.split('=')[1].replace(']','');return node.dataset&&node.dataset.summaryFor===want;}
 if(sel==='.action')return String(node.className||'').split(' ').includes('action');
 if(sel==='.empty')return String(node.className||'').split(' ').includes('empty');
 if(sel==='p')return node.tag==='p';
 return false;
}
function find(node,sel){let found=null;walk(node,n=>{if(!found&&match(n,sel))found=n;});return found;}

function mount(apiImpl){
 const containers=new Map();
 for(const id of ['actions','history-actions','confirm-layer-body','confirm-layer','confirm-layer-close']){
  containers.set(id,el('div'));
 }
 const layer=containers.get('confirm-layer');
 const calls=[];
 const ctx=vm.createContext({
  console,
  document:{
   createElement:tag=>el(tag),
   getElementById:id=>containers.get(id)||null,
   querySelectorAll:()=>[],
   activeElement:null,
  },
  $:id=>containers.get(id)||null,
  node:el,
  money:v=>String(v),
  billingLabel:v=>String(v),
  mandateStatusLabel:v=>String(v),
  statusLabel:{AWAITING_AUTH:'待确认',AUTHORIZED:'已授权',VERIFIED_SUCCESS:'已完成',CANCELLED:'已取消',FAILED:'失败',PENDING:'处理中',UNKNOWN:'未知'},
  receiptMessage:()=>'',
  api:async(...args)=>{calls.push(args);return apiImpl?apiImpl(...args):{};},
  recordSnapshots:new Map(),
  perform:async(_b,fn)=>fn(),
  applyReceipt:()=>{},
  message:()=>{},
  resumeAssociated:async()=>{},
  refresh:async()=>{},
  faultsEnabled:false,
  notify:()=>{},
  updateActionHistory:()=>{},
  selectView:view=>{calls.push(['selectView',view]);},
 });
 vm.runInContext(stateBlock(),ctx);
 vm.runInContext(triageBlock(),ctx);
 for(const name of ['updateActionSummary','showAction','cardError','recordSummary','renderRecord','markRecordUnavailable','goHandleRecord','openConfirmLayer','reopenConfirmLayer','closeConfirmLayer','onConfirmLayerClosed']){
  vm.runInContext(sliceFn(name),ctx);
 }
 vm.runInContext('let confirmLayerFocus=null;let recordNavSequence=0;',ctx);
 return {ctx,containers,layer,calls,
  run:code=>vm.runInContext(code,ctx)};
}

function action(over={}){
 return Object.assign({id:'a1',kind:'QUERY_MANDATES',status:'AWAITING_AUTH',amount_minor:0,payload_hash:'hash-1',title:'代扣查询确认',expires_at:new Date(Date.now()+10*60*1000).toISOString()},over);
}

test('四入口在源码中都改为就地打开确认层',()=>{
 for(const marker of ["openConfirmLayer(await api('/transfers/preview'","openConfirmLayer(await api('/schedules/preview'","openConfirmLayer(await api('/mandates/preview'","openConfirmLayer(await createMandateQueryIntent())"]){
  assert.ok(source.includes(marker),'缺少入口接入: '+marker);
 }
 assert.ok(source.includes("document.getElementById('confirm-layer')"),'缺少确认层容器绑定');
});

test('同笔状态更新留在确认层内，后台更新不弹层',()=>{
 const m=mount();
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run(`showAction({...${JSON.stringify(action())},security:{riskLevel:'RED'}})`);
 assert.equal(m.run(`document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]').length`),1,'更新后仍在层内');
 assert.equal(m.run(`document.getElementById('actions').querySelectorAll('[data-action-id]').length`),0,'记录页不得出现可操作卡');
 m.run(`showAction(${JSON.stringify(action({id:'b2'}))})`);
 const inRecords=m.run(`document.getElementById('actions').querySelectorAll('[data-action-id]').length`);
 assert.equal(m.run(`document.getElementById('actions').querySelectorAll('[data-record-id]').length`),1,'非当前笔在记录页显示为只读记录');
 assert.equal(m.run(`document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]').length`),1,'层内仍是当前笔，未被替换');
});

test('按动作 id 的提交互斥，关闭重开不重复提交',()=>{
 const m=mount();
 assert.equal(m.run('claimSubmit("a1")'),true,'首次应获得提交权');
 assert.equal(m.run('claimSubmit("a1")'),false,'进行中不得再次提交');
 m.run('closeConfirmLayer();onConfirmLayerClosed();');
 assert.equal(m.run('claimSubmit("a1")'),false,'关闭重开不得解除进行中标记');
 m.run('releaseSubmit("a1")');
 assert.equal(m.run('claimSubmit("a1")'),true,'终态解除后可再次处理');
});

test('F1：打开 A → 关闭 → 打开 B 后，A 的迟到更新不得进入当前层',()=>{
 const m=mount();
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('closeConfirmLayer();onConfirmLayerClosed();');
 m.run(`openConfirmLayer(${JSON.stringify(action({id:'b2'}))})`);
 m.run(`showAction(${JSON.stringify(action())})`);
 const ids=m.run(`Array.from(document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]')).map(n=>n.dataset.actionId)`);
 assert.equal(JSON.stringify(ids),JSON.stringify(['b2']),'当前层只能有 B');
 const stash=m.run('layerStash.querySelectorAll("[data-action-id]").map(n=>n.dataset.actionId)');
 assert.equal(JSON.stringify(stash),JSON.stringify([]),'关闭后的单不再进缓存');assert.equal(m.run(`document.getElementById('actions').querySelectorAll('[data-record-id]').length`),1,'A 的更新应显示为只读记录');
});

test('F2：提交响应丢失（AbortError）后仍阻止重复提交',async()=>{
 const calls={authorize:0,submit:0};
 const m=mount(async(path)=>{
  if(String(path).includes('/authorize')){calls.authorize++;return {}};
  if(String(path).includes('/submit')){calls.submit++;const e=Error('aborted');e.name='AbortError';throw e;}
  return {};
 });
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 const button=findButton(m,'确认并查询');
 assert.ok(button,'未找到确认按钮');
 button.onclick();
 await settle();
 assert.equal(calls.submit,1,'首次应发出一次提交');
 assert.equal(m.run('conflicted("a1")'),true,'结果未知必须保持待核实');
 button.onclick();
 await settle();
 assert.equal(calls.submit,1,'结果未知时不得再次提交');
});

test('F2：查单仍 UNKNOWN 不解锁，核实到 AUTHORIZED 后恢复',async()=>{
 let status='UNKNOWN';
 const m=mount(async(path)=>{
  if(String(path).includes('/actions/a1')&&!String(path).includes('/authorize')&&!String(path).includes('/submit'))return {status};
  return {};
 });
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('claimSubmit("a1");unverifiedSubmit("a1")');
 const check=findButton(m,'查询最新状态');
 assert.ok(check,'未找到查单按钮');
 check.onclick();
 await settle();
 assert.equal(m.run('conflicted("a1")'),true,'UNKNOWN 不解锁');
 status='AUTHORIZED';
 check.onclick();
 await settle();
 assert.equal(m.run('conflicted("a1")'),false,'核实到可恢复状态后解锁');
});

test('F2：提交进行中禁止取消，不产生取消请求',async()=>{
 const m=mount();
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('claimSubmit("a1")');
 const cancel=findButton(m,'取消操作');
 assert.ok(cancel,'未找到取消按钮');
 cancel.onclick();
 await settle();
 assert.equal(cancels(m),0,'冲突时不得发出取消请求');
 assert.equal(m.run('conflicted("a1")'),true,'仍保持进行中标记');
});

test('F2：写请求进行中点查单不发起请求',async()=>{
 const m=mount(async()=>({}));
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('claimSubmit("a1")');
 const check=findButton(m,'查询最新状态');
 assert.ok(check,'未找到查单按钮');
 check.onclick();
 await settle();
 const lookups=m.calls.filter(c=>String(c[0]).includes('/actions/a1')).length;
 assert.equal(lookups,0,'进行中不得发起查单');
 m.run('releaseSubmit("a1")');
});

test('F2：旧查单迟到响应不得解除后产生的待核实，界面也不回退',async()=>{
 let releaseLookup=null;
 const m=mount(async(path)=>{
  if(String(path).includes('/authorize'))return {};
  if(String(path).includes('/submit')){const e=Error('aborted');e.name='AbortError';throw e;}
  if(String(path)==='/actions/a1')return new Promise(res=>{releaseLookup=()=>res({id:'a1',kind:'QUERY_MANDATES',status:'AUTHORIZED',amount_minor:0,payload_hash:'hash-1'});});
  return {};
 });
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 const lookup=m.containers.get('confirm-layer-body');
 // 1) 写请求之前发起查单（响应用可控 Promise 挂起）
 const check=findButton(m,'查询最新状态');
 check.onclick();
 await settle();
 assert.equal(typeof releaseLookup,'function','查单应已发出');
 // 2) 再走真实确认：授权成功、提交响应丢失
 const button=findButton(m,'确认并查询');
 button.onclick();
 await settle();
 assert.equal(m.run('conflicted("a1")'),true,'提交丢响应后应锁定');
 const submitsBefore=m.calls.filter(c=>String(c[0]).includes('/submit')).length;
 // 3) 旧查单响应现在到达
 releaseLookup();
 await settle();
 assert.equal(m.run('conflicted("a1")'),true,'旧查单不得解除新保护');
 assert.equal(m.calls.filter(c=>String(c[0]).includes('/submit')).length,submitsBefore,'不得因此再次提交');
 const card=m.run(`(()=>{const b=document.getElementById('confirm-layer-body');const walk=x=>{if(x.dataset&&x.dataset.actionId==='a1')return x;for(const c of x.children||[]){const r=walk(c);if(r)return r;}return null};const n=walk(b);return n?n.dataset.status:''})()`);
 assert.equal(card,'AWAITING_AUTH','界面不得回退成旧的可操作状态');
});

test('F2：授权阶段网络异常也保持待核实，且不重复授权',async()=>{
 const counts={authorize:0};
 const m=mount(async(path)=>{
  if(String(path).includes('/authorize')){counts.authorize++;const e=Error('network');e.name='AbortError';throw e;}
  return {};
 });
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 const button=findButton(m,'确认并查询');
 button.onclick();
 await settle();
 assert.equal(counts.authorize,1,'应发出一次授权请求');
 assert.equal(m.run('conflicted("a1")'),true,'授权不确定应保持待核实');
 button.onclick();
 await settle();
 assert.equal(counts.authorize,1,'不得盲目重复授权');
});

test('F2：完整回包核实到 AUTHORIZED 后恢复实际按钮状态',async()=>{
 let status='UNKNOWN';
 const m=mount(async(path)=>{
  if(String(path)==='/actions/a1')return {id:'a1',kind:'QUERY_MANDATES',status,amount_minor:0,payload_hash:'hash-1'};
  return {};
 });
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('claimSubmit("a1");unverifiedSubmit("a1")');
 const check=findButton(m,'查询最新状态');
 check.onclick();
 await settle();
 assert.equal(m.run('conflicted("a1")'),true,'UNKNOWN 不解锁');
 status='AUTHORIZED';
 check.onclick();
 await settle();
 assert.equal(m.run('conflicted("a1")'),false,'核实到可恢复状态后解锁');
 const cardStatus=m.run(`(()=>{const b=document.getElementById('confirm-layer-body');const walk=x=>{if(x.dataset&&x.dataset.actionId==='a1')return x;for(const c of x.children||[]){const r=walk(c);if(r)return r;}return null};const n=walk(b);return n?n.dataset.status:''})()`);
 assert.equal(cardStatus,'AUTHORIZED','卡片应更新为核实到的状态');
});

test('F1：关闭后收到终态，历史可查且缓存与摘要清理',()=>{
 const m=mount();
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('closeConfirmLayer();onConfirmLayerClosed();');
 m.run(`showAction(${JSON.stringify(action({status:'VERIFIED_SUCCESS'}))})`);
 assert.equal(m.run(`document.getElementById('history-actions').querySelectorAll('[data-action-id]').length`),0,'历史区不得出现可操作卡');assert.equal(m.run(`document.getElementById('history-actions').querySelectorAll('[data-record-id]').length`),1,'历史应出现只读记录');
 assert.equal(m.run(`document.getElementById('actions').querySelectorAll('[data-summary-for]').length`),0,'摘要应清理');
 assert.equal(m.run('layerStash.querySelectorAll("[data-action-id]").length'),0,'缓存不得留副本');
});

test('F1：处理 B 时 A 收到终态，归档 A 不影响 B',()=>{
 const m=mount();
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 m.run('closeConfirmLayer();onConfirmLayerClosed();');
 m.run(`openConfirmLayer(${JSON.stringify(action({id:'b2'}))})`);
 m.run(`showAction(${JSON.stringify(action({status:'VERIFIED_FAILED'}))})`);
 assert.equal(m.run(`document.getElementById('history-actions').querySelectorAll('[data-action-id]').length`),0,'历史区不得出现可操作卡');assert.equal(m.run(`document.getElementById('history-actions').querySelectorAll('[data-record-id]').length`),1,'A 应归档为只读记录');
 assert.equal(m.run('layerStash.querySelectorAll("[data-action-id]").length'),0,'A 不得留在缓存');
 const ids=m.run(`Array.from(document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]')).map(n=>n.dataset.actionId)`);
 assert.equal(JSON.stringify(ids),JSON.stringify(['b2']),'B 仍保持当前');
});

test('F2：取消进行中时反向提交被阻止',async()=>{
 let releaseCancel=null;
 const m=mount(async(path)=>{
  if(String(path).includes('/cancel'))return new Promise(res=>{releaseCancel=()=>res({id:'a1',kind:'QUERY_MANDATES',status:'CANCELLED',amount_minor:0,payload_hash:'hash-1'});});
  return {};
 });
 m.run(`openConfirmLayer(${JSON.stringify(action())})`);
 const cancel=findButton(m,'取消操作');
 cancel.onclick();
 await settle();
 assert.equal(typeof releaseCancel,'function','取消请求应已发出');
 const button=findButton(m,'确认并查询');
 button.onclick();
 await settle();
 assert.equal(m.calls.filter(c=>String(c[0]).includes('/submit')).length,0,'取消未结束前不得反向提交');
 assert.equal(m.run('conflicted("a1")'),true,'仍应在进行中');
 releaseCancel();
 await settle();
});

function buttonsIn(container){
 const out=[];const walk=n=>{if(n.tag==='button')out.push(String(n.text||''));for(const c of n.children||[])walk(c);};walk(container);return out;
}

test('切片A：记录页只读——只有摘要与“去处理”，没有业务控件',()=>{
 const m=mount(async(path)=>({}));
 m.run(`showAction(${JSON.stringify(action())})`);
 const texts=buttonsIn(m.containers.get('actions'));
 assert.equal(JSON.stringify(texts),JSON.stringify(['去处理']),'记录页只能有“去处理”按钮');
 const html=m.run(`document.getElementById('actions').querySelectorAll('[data-action-id]').length`);
 assert.equal(html,0,'记录页不得出现可操作卡');
});

test('切片A：四类原单导航到对应页且不触发业务请求',async()=>{
 const cases=[['TRANSFER','tools'],['REVOKE','mandates'],['QUERY_MANDATES','mandates'],['SCHEDULE','savings']];
 for(const [kind,view] of cases){
  const m=mount(async()=>({id:'x1',kind,status:'AWAITING_AUTH',expires_at:new Date(Date.now()+10*60*1000).toISOString(),amount_minor:0,payload_hash:'h'}));
  m.run('goHandleRecord("x1")');
  await settle();
  const nav=m.calls.filter(c=>c[0]==='selectView').map(c=>c[1]);
  assert.equal(JSON.stringify(nav),JSON.stringify([view]),kind+' 应导航到 '+view);
  const business=m.calls.map(c=>String(c[0])).filter(p=>p.includes('preview')||p.includes('/submit')||p.includes('/cancel')||p.includes('/risk/preview'));
  assert.equal(business.length,0,kind+' 不得触发业务请求');
  m.run('closeConfirmLayer();onConfirmLayerClosed();');
 }
});

test('切片A：过期记录给说明且不开确认层、不新建动作',async()=>{
 const m=mount(async()=>({id:'x1',kind:'TRANSFER',status:'AWAITING_AUTH',expires_at:'2000-01-01T00:00:00Z',amount_minor:0,payload_hash:'h'}));
 m.run('goHandleRecord("x1")');
 await settle();
 assert.equal(m.run(`document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]').length`),0,'过期不得打开可提交卡');
 assert.equal(m.calls.filter(c=>String(c[0]).includes('preview')).length,0,'不得重建动作');
});

test('切片A：较早的迟到读取不得切走当前目标',async()=>{
 let releaseFirst=null;
 const m=mount(async(path)=>{
  if(String(path).includes('slow1'))return new Promise(res=>{releaseFirst=()=>res({id:'slow1',kind:'TRANSFER',status:'AWAITING_AUTH',expires_at:'2099-01-01T00:00:00Z',amount_minor:0,payload_hash:'h'});});
  return {id:'fast2',kind:'SCHEDULE',status:'AWAITING_AUTH',expires_at:'2099-01-01T00:00:00Z',amount_minor:0,payload_hash:'h'};
 });
 m.run('goHandleRecord("slow1")');
 await settle();
 m.run('goHandleRecord("fast2")');
 await settle();
 releaseFirst();
 await settle();
 const ids=m.run(`Array.from(document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]')).map(n=>n.dataset.actionId)`);
 assert.equal(JSON.stringify(ids),JSON.stringify(['fast2']),'迟到的旧读取不得切换当前目标');
});

test('R3：未提交单缺有效期或有效期非法时不提供执行入口',async()=>{
 for(const bad of [undefined,'not-a-date']){
  const rec={id:'s1',kind:'TRANSFER',status:'AWAITING_AUTH',amount_minor:0,payload_hash:'h'};
  rec.expires_at=bad;
  const m=mount(async()=>rec);
  m.run('goHandleRecord("s1")');
  await settle();
  assert.equal(m.run(`document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]').length`),0,'无效有效期不得打开执行入口');
  assert.equal(m.calls.filter(c=>String(c[0]).includes('preview')).length,0,'不得重建动作');
 }
});

test('R3：受理中记录无有效期仍可核对原单',async()=>{
 const m=mount(async()=>({id:'p1',kind:'TRANSFER',status:'PENDING',amount_minor:0,payload_hash:'h'}));
 m.run('goHandleRecord("p1")');
 await settle();
 assert.equal(m.run(`document.getElementById('confirm-layer-body').querySelectorAll('[data-action-id]').length`),1,'受理中记录应可打开原单核对');
});

test('R2：读取失败同 id 只保留一条说明并清理两区残留',async()=>{
 const m=mount(async()=>{throw new Error('read failed')});
 m.run('goHandleRecord("d1")');
 await settle();
 const count=m.run(`document.getElementById('actions').querySelectorAll('[data-record-id]').length + document.getElementById('history-actions').querySelectorAll('[data-record-id]').length`);
 const ids=m.run(`Array.from(document.getElementById('actions').querySelectorAll('[data-record-id]')).concat(Array.from(document.getElementById('history-actions').querySelectorAll('[data-record-id]'))).map(n=>n.dataset.recordId).join(',')`);
 assert.equal(count,1,'同 id 只应保留一条失败说明');
 assert.equal(ids,'d1','失败说明应属于该 id');
});

function findButton(m,label){
 let found=null;
 const walk=n=>{if(found)return;if(n.tag==='button'&&String(n.text||'').includes(label)){found=n;return;}for(const c of n.children||[])walk(c);};
 walk(m.containers.get('confirm-layer-body'));
 return found;
}
async function settle(times=6){for(let i=0;i<times;i++)await new Promise(r=>setTimeout(r,0));}

function cancels(m){return m.calls.filter(c=>String(c[0]).includes('/cancel')).length;}
function submits(m){return m.calls.filter(c=>String(c[0]).includes('/submit')).length;}