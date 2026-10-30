const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

function load(){
 const ctx=vm.createContext({});
 vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/statement-filter.js'),'utf8'),ctx);
 const money=value=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(value||0)/100);
 return {
  summary:vm.runInContext('filterSummary',ctx),
  rows:vm.runInContext('filterRows',ctx),
  money,
 };
}
const data={month:'2026-09',category:'餐饮',direction:'支出',direction_code:'expense',min_amount_minor:10000,
 expense_minor:35001,income_minor:0,matched_count:2,list_scope_note:'合计按全部匹配记录计算；明细可能因容量或隔离被省略，省略不代表没有记录',
 categories:{餐饮:35001},
 transactions:[
  {id:'c',merchant:'餐A',category:'餐饮',amount_minor:-25000,booked_on:'2026-09-04'},
  {id:'b',merchant:'餐B',category:'餐饮',amount_minor:-10001,booked_on:'2026-09-03'},
  {id:'f',merchant:'本人账户',category:'内部转账',amount_minor:-99000,booked_on:'2026-09-07'}]};

test('支出筛选表头带月份类别方向与金额条件，合计与匹配笔数来自服务端',()=>{
 const {summary,money}=load();
 const view=summary(data,money);
 assert.match(view.title,/2026-09/);assert.match(view.title,/餐饮/);assert.match(view.title,/支出/);
 assert.match(view.title,/100\.00/);
 assert.equal(view.totalLabel,'支出合计');
 assert.match(view.total,/350\.01/);
 assert.equal(view.matches,'2 笔');
 assert.equal(view.empty,false);
 assert.match(view.note,/省略不代表没有记录/);
});

test('收入筛选使用入账合计标签，不显示支出分类',()=>{
 const {summary,money}=load();
 const income=summary({...data,direction:'收入',direction_code:'income',income_minor:120000,expense_minor:0,min_amount_minor:0,categories:{收入:120000},transactions:[]},money);
 assert.equal(income.totalLabel,'入账合计（含退款）');
 assert.match(income.total,/1,200\.00/);
 assert.equal(income.income,true);
 assert.doesNotMatch(income.title.slice(0,income.title.indexOf('单笔')<0?income.title.length:income.title.indexOf('单笔')),/支出/);
});

test('无匹配时提示该条件下没有记录，不写整月没有记录',()=>{
 const {summary,money}=load();
 const empty=summary({...data,matched_count:0,expense_minor:0,transactions:[],categories:{}},money);
 assert.equal(empty.empty,true);
 assert.match(empty.emptyText,/该条件下的/);assert.match(empty.emptyText,/2026-09/);assert.match(empty.emptyText,/餐饮/);
 assert.doesNotMatch(empty.emptyText,/整月/);
});

test('逐笔方向按符号判定，内部转账单独标识',()=>{
 const {rows}=load();
 const mapped=rows(data);
 assert.deepEqual(mapped.map(row=>row.direction),['支出','支出','内部转账']);
 assert.deepEqual(mapped.map(row=>row.magnitude),[25000,10001,99000]);
});
