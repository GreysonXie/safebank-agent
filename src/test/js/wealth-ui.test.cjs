const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

function createElement(tag){
 const el={tagName:String(tag).toUpperCase(),children:[],textContent:'',className:'',hidden:false,dataset:{},value:'',checked:false,disabled:false,type:'',style:{}};
 el.append=(...nodes)=>{for(const node of nodes)el.children.push(node);};
 el.replaceChildren=(...nodes)=>{el.children=nodes.slice();};
 el.querySelectorAll=selector=>{
  const found=[];const visit=node=>{if(!node||!node.children)return;for(const child of node.children){if(selector==='input'&&child.tagName==='INPUT')found.push(child);visit(child);}};
  visit(el);return found;
 };
 el.addEventListener=()=>{};
 return el;
}
function setup(overrideApi){
 const document={createElement};
 const ctx=vm.createContext({document});
 vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/wealth-ui.js'),'utf8'),ctx);
 const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
 const money=value=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(value||0)/100);
 const calls=[];const asked=[];
 const api=overrideApi||(path=>{calls.push(path);return Promise.resolve(catalogData('rev-0001'));});
 const askChat=text=>{asked.push(text);return {sent:true};};
 const mount=vm.runInContext('createWealthPanel',ctx)({node,money,api,askChat});
 const historySummary=vm.runInContext('historySummary',ctx);
 return {mount,node,money,calls,asked,api,historySummary};
}
function catalogData(revision){
 return {dataset_id:'safebank-wealth-demo-v1',catalog_revision:revision||'rev-0001',reference_checked_on:'2026-10-30',synthetic:true,count:3,
 catalog_scope:'仅本项目演示目录',risk_scale_note:'演示统一采用R1至R5',
 products:[
  {id:'DEMO-WM-01',name:'SafeBank演示·活钱管理',display_name:'活钱管理',product_type:'现金管理类',operation_mode:'开放式',risk_level:'R1',risk_note:'非存款、不保本',term_days:null,term_note:'无固定产品到期日',min_holding_days:0,min_amount_minor:100,redemption_rule:'演示设定：无最低持有期',settlement_note:'到账时效未提供',expected_return:'未提供',return_note:'没有导入收益率',fee_note:'未提供，不等于零费用'},
  {id:'DEMO-WM-02',name:'SafeBank演示·固收90天持有',display_name:'固收90天持有',product_type:'固定收益类',operation_mode:'开放式、最低持有期型',risk_level:'R2',risk_note:'可能有净值波动',term_days:null,term_note:'无固定产品到期日；90天为最低持有约束，不自动到期',min_holding_days:90,min_amount_minor:100,redemption_rule:'演示设定：最低持有90个自然日',settlement_note:'到账时效未提供',expected_return:'未提供',return_note:'未导入业绩比较基准',fee_note:'未提供，不等于零费用'},
  {id:'DEMO-WM-03',name:'SafeBank演示·固收180天持有',display_name:'固收180天持有',product_type:'固定收益类',operation_mode:'开放式、最低持有期型',risk_level:'R3',risk_note:'可能亏损',term_days:null,term_note:'无固定产品到期日；180天为最低持有约束，不自动到期',min_holding_days:180,min_amount_minor:100000,redemption_rule:'演示设定：最低持有180个自然日',settlement_note:'到账时效未提供',expected_return:'未提供',return_note:'未导入收益率',fee_note:'未提供，不等于零费用'}]};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function text(el){const out=[];const visit=node=>{if(!node)return;out.push(node.textContent||'');for(const child of node.children||[])visit(child);};visit(el);return out.join(' ');}
function boxes(view){return view.list.querySelectorAll('input');}

test('查询与展示使用正常业务名称，不出现演示标识',async()=>{
 const {mount,node}=setup();const host=node('div');const view=mount(host);
 await view.query.onclick();await flush();
 const shown=text(host);
 assert.match(shown,/共 3 个产品/);
 assert.match(shown,/活钱管理/);assert.match(shown,/固收90天持有/);
 assert.doesNotMatch(shown,/演示/);assert.doesNotMatch(shown,/模拟/);
 assert.equal(view.query.textContent,'查看产品');
});

test('两个产品同字段对比，期限与最低持有期不混淆',async()=>{
 const {mount,node}=setup();const host=node('div');const view=mount(host);
 await view.query.onclick();await flush();
 const inputs=boxes(view);inputs[1].checked=true;inputs[2].checked=true;view.showCompare();
 const shown=text(view.compare);
 assert.match(shown,/产品对比/);
 assert.match(shown,/无固定产品到期日/);
 assert.match(shown,/90 个自然日/);assert.match(shown,/180 个自然日/);
 assert.match(shown,/1,000\.00/);assert.match(shown,/未提供/);
});

test('请助手解释先经服务端校验，成功后把两个规范产品交给聊天',async()=>{
 const calls=[];
 const api=path=>{calls.push(path);
  if(path.startsWith('/wealth/validate'))return Promise.resolve({valid:true,products:[{id:'DEMO-WM-02',display_name:'固收90天持有'},{id:'DEMO-WM-03',display_name:'固收180天持有'}]});
  return Promise.resolve(catalogData('rev-0001'));};
 const {mount,node,asked}=setup(api);
 const host=node('div');const view=mount(host);
 await view.query.onclick();await flush();
 const inputs=boxes(view);inputs[1].checked=true;inputs[2].checked=true;view.showCompare();
 assert.equal(view.explain.disabled,false);
 await view.explain.onclick();
 const validateCalls=calls.filter(item=>item.startsWith('/wealth/validate'));
 assert.equal(validateCalls.length,1);
 assert.match(validateCalls[0],/ids=DEMO-WM-02%2CDEMO-WM-03&revision=rev-0001/);
 assert.equal(asked.length,1);
 assert.match(asked[0],/固收90天持有和固收180天持有/);
 assert.match(asked[0],/DEMO-WM-02、DEMO-WM-03/);
 assert.match(text(view.compare),/产品对比/);
});

test('目录版本过期时不发解释请求并保留原表格',async()=>{
 const api=path=>{
  if(path.startsWith('/wealth/validate')){const error=Error('产品目录已更新');error.code='CATALOG_STALE';return Promise.reject(error);}
  return Promise.resolve(catalogData('rev-0001'));};
 const {mount,node}=setup(api);const host=node('div');const view=mount(host);
 await view.query.onclick();await flush();
 const inputs=boxes(view);inputs[0].checked=true;inputs[1].checked=true;view.showCompare();
 await view.explain.onclick();
 assert.match(view.explainHint.textContent,/已更新/);
 assert.match(text(view.compare),/产品对比/);
});

test('未选满两个时按钮禁用，迟到校验结果不会发出聊天',async()=>{
 let resolveValidate;
 const api=path=>{
  if(path.startsWith('/wealth/validate'))return new Promise(resolve=>{resolveValidate=resolve;});
  return Promise.resolve(catalogData('rev-0001'));};
 const document={createElement};
 const ctx=vm.createContext({document});
 vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/wealth-ui.js'),'utf8'),ctx);
 const node=(tag,text,cls)=>{const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(cls)el.className=cls;return el;};
 const money=value=>'¥'+Number(value||0)/100;
 const asked=[];
 const mount=vm.runInContext('createWealthPanel',ctx)({node,money,api,askChat:text=>{asked.push(text);return {sent:true};}});
 const host=node('div');const view=mount(host);
 await view.query.onclick();await flush();
 assert.equal(view.explain.disabled,true);
 const inputs=boxes(view);inputs[1].checked=true;inputs[2].checked=true;view.showCompare();
 const pending=view.explain.onclick();
 inputs[0].checked=true;inputs[1].checked=true;view.showCompare();
 resolveValidate({valid:true,products:[{id:'DEMO-WM-02',display_name:'固收90天持有'},{id:'DEMO-WM-03',display_name:'固收180天持有'}]});
 await pending;await flush();
 assert.equal(asked.length,0);
});

test('历史表现显示实际区间、单位净值与带符号的区间变化',()=>{
 const {historySummary}=setup();
 const view=historySummary({product:'DEMO-WM-03',display_name:'固收180天持有',actual_from:'2026-09-01',actual_to:'2026-09-30',requested_from:'2026-09-01',requested_to:'2026-09-30',records:22,nav_from:'1.0000',nav_to:'0.9931',change_percent:-0.69,coverage_note:'工作日样本（周一到周五），未核实节假日'},value=>value);
 assert.match(view.title,/固收180天持有/);
 assert.match(view.range,/2026-09-01 至 2026-09-30/);assert.match(view.range,/22 条样本/);
 assert.match(view.change,/1\.0000 → 0\.9931 元\/份/);
 assert.match(view.change,/-0\.69%/);
 assert.doesNotMatch(view.change,/分/);
 assert.match(view.note,/工作日样本/);
});
test('历史表现显示最大回撤与峰谷依据，无回撤时明确说明',()=>{
 const {historySummary}=setup();
 const withDrawdown=historySummary({product:'DEMO-WM-02',display_name:'固收90天持有',actual_from:'2026-09-01',actual_to:'2026-09-30',requested_from:'2026-09-01',requested_to:'2026-09-30',records:22,nav_from:'1.0000',nav_to:'1.0042',change_percent:0.42,max_drawdown_percent:10,drawdown_peak_date:'2026-09-02',drawdown_peak_nav:'1.1000',drawdown_trough_date:'2026-09-03',drawdown_trough_nav:'0.9900',coverage_note:'工作日样本'},v=>v);
 assert.match(withDrawdown.drawdown,/10\.00%/);
 assert.match(withDrawdown.drawdown,/2026-09-02/);assert.match(withDrawdown.drawdown,/2026-09-03/);
 assert.match(withDrawdown.change,/0\.42%/);
 const flat=historySummary({product:'DEMO-WM-03',display_name:'固收180天持有',actual_from:'2026-09-01',actual_to:'2026-09-30',records:22,nav_from:'1.0000',nav_to:'1.0100',change_percent:1.00,max_drawdown_percent:0,coverage_note:'工作日样本'},v=>v);
 assert.match(flat.drawdown,/未出现回撤/);
});