const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

const UI=path.resolve(__dirname,'../../main/resources/static/wealth-ui.js');
const source=fs.readFileSync(UI,'utf8');

function monthBlock(){
 const begin=source.indexOf('// --- wealth-history-month:begin ---');
 const end=source.indexOf('// --- wealth-history-month:end ---');
 assert.ok(begin>=0&&end>begin,'wealth-ui.js 中缺少 wealth-history-month 标记块');
 return source.slice(begin,end);
}

function mount(){
 const ctx=vm.createContext({Date,Math});
 vm.runInContext(monthBlock()+'\nthis.currentMonthValue=currentMonthValue;this.shiftMonthValue=shiftMonthValue;',ctx);
 return ctx;
}

test('历史月份默认取真实本月，不固定在某个历史月份',()=>{
 const {currentMonthValue}=mount();
 assert.equal(currentMonthValue('2026-10-31T02:00:00Z'),new Date('2026-10-31T02:00:00Z').getFullYear()+'-'+String(new Date('2026-10-31T02:00:00Z').getMonth()+1).padStart(2,'0'));
 const real=currentMonthValue();
 const now=new Date();
 assert.equal(real,now.getFullYear()+'-'+String(now.getMonth()+1).padStart(2,'0'),'默认必须是运行时的本月');
 assert.ok(!/const historyMonth=node\('input'\);historyMonth\.type='month';historyMonth\.value='2026-09'/.test(source),'代码里不应再写死 2026-09');
});

test('上一个月按钮跨年回退正确',()=>{
 const {shiftMonthValue}=mount();
 assert.equal(shiftMonthValue('2026-10',-1),'2026-09');
 assert.equal(shiftMonthValue('2026-01',-1),'2025-12');
 assert.equal(shiftMonthValue('2026-12',-1),'2026-11');
 assert.equal(shiftMonthValue('坏值',-1),shiftMonthValue(new Date().getFullYear()+'-'+String(new Date().getMonth()+1).padStart(2,'0'),0),'读不懂时退回本月');
});
