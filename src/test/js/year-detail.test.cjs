const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

function createElement(tag){
 const el={tagName:String(tag).toUpperCase(),children:[],textContent:'',className:'',hidden:false,dataset:{},type:'',style:{}};
 el.append=(...nodes)=>{for(const node of nodes)el.children.push(node);};
 el.replaceChildren=(...nodes)=>{el.children=nodes.slice();};
 return el;
}
function setup(){
 const document={createElement};
 const ctx=vm.createContext({document});
 vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/year-detail.js'),'utf8'),ctx);
 const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
 const money=value=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(value||0)/100);
 const calls=[];
 const api=path=>new Promise((resolve,reject)=>calls.push({path,resolve,reject}));
 const mount=vm.runInContext('createYearMonths',ctx)({node,money,api});
 return {mount,node,calls};
}
function walk(el,out=[]){out.push(el);for(const child of el.children||[])walk(child,out);return out;}
function text(el){return walk(el).map(item=>item.textContent||'').join(' ');}
function find(el,predicate){return walk(el).find(predicate);}
const yearData={year:'2026',expense_minor:3011820,income_minor:5420000,internal_transfer_minor:600000,record_count:118,
 categories:{餐饮:104689},months:[{month:'2026-09',expense_minor:536933,income_minor:1170000,internal_transfer_minor:100000,transaction_count:19},
  {month:'2026-10',expense_minor:80300,income_minor:0,internal_transfer_minor:0,transaction_count:5}]};
const september={month:'2026-09',expense_minor:536933,income_minor:1170000,internal_transfer_minor:100000,categories:{餐饮:18424},
 transactions:[{id:'t1',merchant:'早餐铺',category:'餐饮',amount_minor:-18424,booked_on:'2026-09-03'},
  {id:'t2',merchant:'工资',category:'收入',amount_minor:1170000,booked_on:'2026-09-01'},
  {id:'t3',merchant:'本人旅游账户',category:'内部转账',amount_minor:-100000,booked_on:'2026-09-10'}]};
const flush=()=>new Promise(resolve=>setImmediate(resolve));

test('点击月份从受鉴权月报接口取数并按方向显示逐笔',async()=>{
 const {mount,node,calls}=setup();const host=node('div');const summary=node('h3','年度账单');host.append(summary);
 const view=mount(host,yearData);
 const button=find(view.list,el=>el.dataset.month==='2026-09');
 button.onclick();
 assert.equal(calls.length,1);assert.equal(calls[0].path,'/statement?month=2026-09');
 calls[0].resolve(september);await flush();
 const shown=text(view.detail);
 assert.match(shown,/2026-09 明细/);
 assert.match(shown,/支出 ¥5,369\.33/);assert.match(shown,/收入 ¥11,700\.00/);assert.match(shown,/内部转账 ¥1,000\.00/);
 assert.match(shown,/2026-09-03 · 早餐铺 · 支出 · ¥184\.24/);
 assert.match(shown,/2026-09-10 · 本人旅游账户 · 内部转账 · ¥1,000\.00/);
 assert.equal(host.children[0],summary);
});

test('无已记录交易的月份明确提示且不残留上一个月内容',async()=>{
 const {mount,node,calls}=setup();const host=node('div');const view=mount(host,yearData);
 view.open('2026-09');calls[0].resolve(september);await flush();
 assert.match(text(view.detail),/早餐铺/);
 view.open('2026-10');calls[1].resolve({month:'2026-10',expense_minor:0,income_minor:0,internal_transfer_minor:0,categories:{},transactions:[]});await flush();
 const shown=text(view.detail);
 assert.match(shown,/这个月份没有已记录交易/);assert.doesNotMatch(shown,/早餐铺/);
});

test('读取失败给出提示并可重试，错误码不当空月报',async()=>{
 const {mount,node,calls}=setup();const host=node('div');const view=mount(host,yearData);
 view.open('2026-09');calls[0].reject(Object.assign(new Error('网络中断'),{code:'NETWORK'}));await flush();
 const failed=text(view.detail);
 assert.match(failed,/读取失败/);assert.doesNotMatch(failed,/没有已记录交易/);
 const retry=find(view.detail,el=>el.tagName==='BUTTON'&&el.textContent==='重试');
 retry.onclick();
 assert.equal(calls.length,2);assert.equal(calls[1].path,'/statement?month=2026-09');
 calls[1].resolve(september);await flush();
 assert.match(text(view.detail),/早餐铺/);
 view.open('2026-10');calls[2].resolve({code:'VALIDATION_ERROR',message:'月份格式或范围无效'});await flush();
 assert.match(text(view.detail),/读取失败/);
});

test('迟到的旧响应不能覆盖新的月份选择',async()=>{
 const {mount,node,calls}=setup();const host=node('div');const view=mount(host,yearData);
 view.open('2026-09');view.open('2026-10');
 calls[1].resolve({month:'2026-10',expense_minor:80300,income_minor:0,internal_transfer_minor:0,categories:{},transactions:[{id:'o1',merchant:'超市',category:'购物',amount_minor:-80300,booked_on:'2026-10-05'}]});
 await flush();
 calls[0].resolve(september);await flush();
 const shown=text(view.detail);
 assert.match(shown,/2026-10 明细/);assert.match(shown,/超市/);
 assert.doesNotMatch(shown,/2026-09 明细/);assert.doesNotMatch(shown,/早餐铺/);
});

test('收起明细后迟到的响应不会重新展开',async()=>{
 const {mount,node,calls}=setup();const host=node('div');const view=mount(host,yearData);
 view.open('2026-09');
 view.open('2026-10');
 calls[1].resolve({month:'2026-10',expense_minor:80300,income_minor:0,internal_transfer_minor:0,categories:{},transactions:[{id:'o1',merchant:'超市',category:'购物',amount_minor:-80300,booked_on:'2026-10-05'}]});
 await flush();
 const close=find(view.detail,el=>el.tagName==='BUTTON'&&el.textContent==='收起明细');
 close.onclick();
 assert.equal(view.detail.hidden,true);assert.equal(view.detail.children.length,0);
 calls[0].resolve(september);await flush();
 assert.equal(view.detail.hidden,true);assert.equal(view.detail.children.length,0);
 assert.doesNotMatch(text(view.detail),/早餐铺/);
});

test('商户等记录文本按纯文本显示，不创建元素',async()=>{
 const {mount,node,calls}=setup();const host=node('div');const view=mount(host,yearData);
 view.open('2026-09');
 calls[0].resolve({month:'2026-09',expense_minor:100,income_minor:0,internal_transfer_minor:0,categories:{},
  transactions:[{id:'x',merchant:'<img src=x onerror=alert(1)>',category:'餐饮',amount_minor:-100,booked_on:'2026-09-02'}]});
 await flush();
 const img=find(view.detail,el=>el.tagName==='IMG');
 assert.equal(img,undefined);
 assert.match(text(view.detail),/<img src=x onerror=alert\(1\)>/);
});

test('年度汇总节点保持可用，月份入口带年份与笔数',()=>{
 const {mount,node}=setup();const host=node('div');const summary=node('h3','2026 年度账单');host.append(summary);
 const view=mount(host,yearData);
 assert.equal(host.children[0],summary);
 const buttons=view.list.children;
 assert.equal(buttons.length,2);
 assert.match(buttons[0].textContent,/2026-09/);assert.match(buttons[0].textContent,/19 笔/);
 assert.equal(buttons[0].type,'button');
});
