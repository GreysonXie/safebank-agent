const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

const APP=path.resolve(__dirname,'../../main/resources/static/app.js');
const source=fs.readFileSync(APP,'utf8');

// 纯逻辑块：只加载被显式标记的一段，不运行 app.js 的顶层副作用。
function logicBlock(){
 const begin=source.indexOf('// --- renewal-notice:begin');
 const end=source.indexOf('// --- renewal-notice:end ---');
 assert.ok(begin>=0&&end>begin,'app.js 中缺少 renewal-notice 标记块');
 return source.slice(begin,end);
}
// 生产渲染函数：按函数边界切片，测试的是 app.js 里的真实实现，不是复制品。
function renderBlock(){
 const start=source.indexOf('function renderMandates(');
 assert.ok(start>=0,'app.js 中未找到 renderMandates');
 const end=source.indexOf('\n}',start);
 assert.ok(end>start,'renderMandates 边界解析失败');
 const body=source.slice(start,end+2);
 assert.ok(body.includes('mandateRenewalLine('),'renderMandates 未接入续费提醒');
 return body;
}

function fakeNode(tag,text,className){
 const node={tag,text:text===undefined||text===null?'':String(text),className:className||'',children:[],
  append(...items){node.children.push(...items);},
  replaceChildren(...items){node.children=items;},
  querySelectorAll(){return [];},
  scrollIntoView(){},
  setAttribute(){},
  addEventListener(){},
  classList:{add(){},remove(){},toggle(){},contains(){return false}},
  dataset:{},style:{},disabled:false,type:'',value:'',checked:false,onclick:null};
 return node;
}

function mount(){
 const containers=new Map();
 for(const id of ['mandates','workflow-mandates'])containers.set(id,fakeNode('div'));
 const ctx=vm.createContext({
  Date,Math,
  chatBusy:false,
  confirmedMandates:null,
  mandateBusinessDate:'',
  mandateBusinessState:'idle',
  node:fakeNode,
  money:minor=>String((minor||0)/100),
  billingLabel:value=>String(value),
  mandateStatusLabel:value=>String(value),
  showAction(){},
  notify(){},
  sendQuestion(){},
  createMandateQueryIntent:async()=>({}),
  perform:async(button,action)=>action(),
  api:async()=>({}),
  $:id=>containers.get(id),
 });
 vm.runInContext(logicBlock(),ctx);
 vm.runInContext(renderBlock(),ctx);
 return {
  ctx,
  render:mandates=>vm.runInContext(`renderMandates(${JSON.stringify(mandates)})`,ctx),
  setDate:value=>vm.runInContext(`mandateBusinessDate=${JSON.stringify(value)}`,ctx),
  container:id=>containers.get(id),
 };
}

function texts(node,out=[]){
 if(!node)return out;
 if(node.text)out.push(node.text);
 for(const child of node.children||[])texts(child,out);
 return out;
}
function renewalNodes(container){
 const found=[];
 const walk=item=>{
  if(!item)return;
  if(String(item.className||'').split(' ').includes('renewal-note'))found.push(item);
  for(const child of item.children||[])walk(child);
 };
 walk(container);
 return found;
}

test('renewalNotice 覆盖今天、1 天、7 天、8 天、已过去、缺失、非法格式、非法日历、跨月',()=>{
 const t=mount();
 const call=(due,status,biz)=>vm.runInContext(`renewalNotice(${JSON.stringify(due)},${JSON.stringify(status)},${JSON.stringify(biz)})`,t.ctx);
 assert.equal(call('2026-10-30','ACTIVE','2026-10-30').kind,'today');
 assert.equal(call('2026-10-31','ACTIVE','2026-10-30').note,'还有 1 天到期');
 assert.equal(call('2026-11-06','ACTIVE','2026-10-30').note,'还有 7 天到期');
 assert.equal(call('2026-11-07','ACTIVE','2026-10-30').note,'');
 assert.equal(call('2026-11-07','ACTIVE','2026-10-30').kind,'later');
 assert.equal(call('2026-10-29','ACTIVE','2026-10-30').kind,'past');
 assert.equal(call(null,'ACTIVE','2026-10-30').note,'暂无可靠续费日期');
 assert.equal(call('2026-2-3','ACTIVE','2026-10-30').kind,'unknown');
 assert.equal(call('2026-02-30','ACTIVE','2026-10-30').kind,'unknown');
 assert.equal(call('2026-11-02','ACTIVE','2026-02-30').kind,'business-unknown');
 assert.equal(call('2026-11-02','REVOKED','2026-10-30').kind,'inactive');
 assert.equal(call('2026-02-02','ACTIVE','2026-01-30').note,'还有 3 天到期');
});

test('renewalNotice 用日历日计算跨月与跨年，不做字符串相减',()=>{
 const t=mount();
 const call=(due,biz)=>vm.runInContext(`renewalNotice(${JSON.stringify(due)},'ACTIVE',${JSON.stringify(biz)})`,t.ctx);
 assert.equal(call('2027-01-01','2026-12-30').note,'还有 2 天到期');
 assert.equal(call('2026-03-01','2026-02-27').note,'还有 2 天到期');
});

test('未确认查询时不展示任何续费日期或提醒',()=>{
 const t=mount();
 t.render(null);
 const all=texts(t.container('mandates')).join(' | ');
 assert.match(all,/确认查询范围/);
 assert.doesNotMatch(all,/记录续费日|还有 \d+ 天|今天到期|记录日期已过/);
 assert.equal(renewalNodes(t.container('mandates')).length,0);
});

test('确认后提醒落在对应代扣条目内，重复渲染不堆叠',()=>{
 const t=mount();
 t.setDate('2026-10-30');
 const list=[
  {id:'m1',merchant:'音乐会员',amount_minor:1500,status:'ACTIVE',renewal_date:'2026-11-02',billing_interval:'MONTHLY'},
  {id:'m2',merchant:'视频会员',amount_minor:6800,status:'REVOKED',renewal_date:'2026-11-02',billing_interval:'MONTHLY'},
 ];
 t.render(list);
 const first=renewalNodes(t.container('mandates'));
 assert.equal(first.length,2,'有效与已撤销各一条：已撤销不提示即将续费');
 assert.match(first[0].text,/记录续费日 2026-11-02/);
 assert.match(first[0].text,/还有 3 天到期/);
 assert.doesNotMatch(first[1].text,/还有 3 天到期/);

 t.render(list);
 const second=renewalNodes(t.container('mandates'));
 assert.equal(second.length,2,'重复渲染不得叠加提醒节点');
});

test('重新查询后相对天数随新业务日期更新，失败时降级不保留旧倒计时',()=>{
 const t=mount();
 const list=[{id:'m1',merchant:'音乐会员',amount_minor:1500,status:'ACTIVE',renewal_date:'2026-11-02',billing_interval:'MONTHLY'}];
 t.setDate('2026-10-30');
 t.render(list);
 assert.match(renewalNodes(t.container('mandates'))[0].text,/还有 3 天到期/);

 t.setDate('2026-11-01');
 t.render(list);
 assert.match(renewalNodes(t.container('mandates'))[0].text,/还有 1 天到期/);

 t.setDate('');
 t.render(list);
 const degraded=renewalNodes(t.container('mandates'))[0].text;
 assert.match(degraded,/记录续费日 2026-11-02/);
 assert.match(degraded,/暂不能判断是否临近/);
 assert.doesNotMatch(degraded,/还有 \d+ 天/,'失败后不得残留旧倒计时');
});

test('分组按紧急度排序：即将到期在未到期之前，已过记录日单独成组，已撤销最后',()=>{
 const t=mount();
 t.setDate('2026-10-30');
 const list=[
  {id:'later',merchant:'远期会员',amount_minor:1000,status:'ACTIVE',renewal_date:'2026-11-20',billing_interval:'MONTHLY'},
  {id:'past',merchant:'已过会员',amount_minor:1000,status:'ACTIVE',renewal_date:'2026-10-20',billing_interval:'MONTHLY'},
  {id:'soon',merchant:'临近会员',amount_minor:1000,status:'ACTIVE',renewal_date:'2026-11-02',billing_interval:'MONTHLY'},
  {id:'revoked',merchant:'已撤销会员',amount_minor:1000,status:'REVOKED',renewal_date:'2026-10-20',billing_interval:'MONTHLY'},
 ];
 t.render(list);
 const all=texts(t.container('mandates')).join(' | ');
 const order=['即将到期','未到期','日期需核对','已撤销'].map(word=>all.indexOf(word));
 assert.ok(order.every(index=>index>=0),'四个分组标题都在：'+all);
 assert.ok(order[0]<order[1]&&order[1]<order[2]&&order[2]<order[3],'顺序应为 临近→未到期→已过记录日→已撤销，实际 '+JSON.stringify(order));
 assert.match(all,/共 4 项：即将到期 1 · 未到期 1 · 日期需核对 1 · 已撤销 1/);
 assert.match(all,/记录的续费日已过，授权仍有效，可能已扣款或记录没更新/);
 assert.match(all,/银行侧授权已解除，不是/);
});
test('撤销预览卡片解释这是什么、确认后会怎样、不会发生什么',()=>{
 const source=fs.readFileSync(APP,'utf8');
 const start=source.indexOf('function showAction(a){');
 assert.ok(start>=0,'app.js 中未找到 showAction');
 const end=source.indexOf('let recordNavSequence',start);
 const body=source.slice(start,end);
 assert.match(body,/这是什么：撤销这张银行侧代扣授权的预览/);
 assert.match(body,/确认之后：银行侧这张授权会被解除/);
 assert.match(body,/不会发生：不会退款，余额不会因此增加，也不代表商户合同已经终止/);
 assert.match(body,/你需要做：核对上面的商户、单期金额和周期/);
 assert.match(body,/a\.kind==='REVOKE'&&\['AWAITING_AUTH','AUTHORIZED'\]\.includes\(a\.status\)/,'说明只出现在待确认的撤销预览上，不污染已完成的记录');
});