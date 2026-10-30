const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

const APP=path.resolve(__dirname,'../../main/resources/static/app.js');
const source=fs.readFileSync(APP,'utf8');

function triageBlock(){
 const begin=source.indexOf('// --- record-triage:begin');
 const end=source.indexOf('// --- record-triage:end ---');
 assert.ok(begin>=0&&end>begin,'app.js 中缺少 record-triage 标记块');
 return source.slice(begin,end);
}
function summaryBlock(){
 const start=source.indexOf('function recordSummary(');
 assert.ok(start>=0,'app.js 中未找到 recordSummary');
 const end=source.indexOf('\n}',start);
 assert.ok(end>start,'recordSummary 边界解析失败');
 return source.slice(start,end+2);
}

function fakeNode(tag,text,className){
 const node={tag,text:text===undefined||text===null?'':String(text),className:className||'',children:[],dataset:{},type:'',disabled:false,onclick:null,
  append(...items){node.children.push(...items);},
  querySelectorAll(){return [];},
  querySelector(){return null;}};
 return node;
}

function mount(){
 const ctx=vm.createContext({
  Date,Math,
  statusLabel:{AWAITING_AUTH:'待确认 · 尚未执行',AUTHORIZED:'已授权 · 尚未提交',VERIFIED_SUCCESS:'已核验完成',VERIFIED_FAILED:'已核验失败',CANCELLED:'已取消',PENDING:'银行处理中',UNKNOWN:'状态待核实'},
  node:fakeNode,
  goHandleRecord(id){ctx.handled=(ctx.handled||[]).concat(id);},
 });
 vm.runInContext(triageBlock()+'\n'+summaryBlock(),ctx);
 return ctx;
}

const NOW=Date.now();                              // 用真实当前时刻，避免测试和机器时区脱节
const soon=new Date(NOW+4*60*1000).toISOString();   // 还有 4 分钟有效期
const past=new Date(NOW-5*60*1000).toISOString();   // 5 分钟前就过期了

test('未过期的待确认留在待确认栏，过期的进处理记录',()=>{
 const t=mount();
 assert.equal(t.recordArchived({id:'a',status:'AWAITING_AUTH',expires_at:soon},NOW),false);
 assert.equal(t.recordArchived({id:'b',status:'AWAITING_AUTH',expires_at:past},NOW),true);
 assert.equal(t.recordHeadline({id:'b',status:'AWAITING_AUTH',expires_at:past},NOW),'已过期 · 尚未执行');
 assert.equal(t.recordHeadline({id:'a',status:'AWAITING_AUTH',expires_at:soon},NOW),'待确认 · 尚未执行');
});

test('已授权未提交只要没过期就还能继续办理',()=>{
 const t=mount();
 assert.equal(t.recordArchived({id:'c',status:'AUTHORIZED',expires_at:soon},NOW),false);
 assert.equal(t.recordArchived({id:'d',status:'AUTHORIZED',expires_at:past},NOW),true);
});

test('已取消、已完成、已失败一律进处理记录',()=>{
 const t=mount();
 for(const status of ['CANCELLED','VERIFIED_SUCCESS','VERIFIED_FAILED'])
  assert.equal(t.recordArchived({id:status,status,expires_at:soon},NOW),true,status+' 应归档');
});

test('银行处理中/状态待核实不算完成，不能藏进折叠的处理记录',()=>{
 const t=mount();
 assert.equal(mount().recordArchived({id:'p',status:'PENDING',expires_at:past},NOW),false);
 assert.equal(t.recordArchived({id:'u',status:'UNKNOWN',expires_at:past},NOW),false);
 assert.equal(t.recordHeadline({id:'p',status:'PENDING'},NOW),'银行处理中');
});

test('有效期缺失或读不懂时按不能继续办理处理',()=>{
 const t=mount();
 assert.equal(t.recordArchived({id:'m',status:'AWAITING_AUTH'},NOW),true);
 assert.equal(t.recordArchived({id:'n',status:'AWAITING_AUTH',expires_at:'不是日期'},NOW),true);
});

test('过期行不给“去处理”按钮，并说明旧单不能延长有效期',()=>{
 const t=mount();
 const expired=t.recordSummary({id:'x',kind:'TRANSFER',status:'AWAITING_AUTH',expires_at:past});
 const expiredText=expired.children.map(child=>child.text||(child.children||[]).map(c=>c.text).join('')).join(' ');
 assert.match(expiredText,/已过期 · 尚未执行/);
 assert.match(expiredText,/如需办理请在业务页重新发起/);
 assert.ok(!expired.children.some(child=>child.tag==='button'),'过期行不应还有去处理按钮');
 const live=t.recordSummary({id:'y',kind:'TRANSFER',status:'AWAITING_AUTH',expires_at:soon});
 assert.ok(live.children.some(child=>child.tag==='button'&&child.text==='去处理'),'未过期行应保留去处理');
});

test('渲染分流用的是分流函数而不是旧的三状态写死',()=>{
 const start=source.indexOf('function renderRecord(');
 const end=source.indexOf('\n}',start);
 const renderRecord=source.slice(start,end+2);
 assert.ok(!/const archived=\['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'\]/.test(renderRecord),'renderRecord 不应再写死三状态');
 assert.match(renderRecord,/const archived=recordArchived\(a,Date\.now\(\)\);/);
 assert.match(source,/function sweepExpiredRecords\(\)/,);
 assert.match(source,/sweepExpiredRecords\(\);/,);
});
