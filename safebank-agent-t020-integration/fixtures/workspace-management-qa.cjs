// Browser acceptance against a disposable synthetic bank. Never sends a model request.
const path=require('node:path');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const base=process.env.SAFEBANK_QA_URL||'http://127.0.0.1:8136';
const out=path.resolve(__dirname,'../target/workspace-qa');
fs.mkdirSync(out,{recursive:true});
const checks=[];let modelRequests=0;
function passed(name){checks.push(name);console.log('PASS '+name);}
async function call(page,endpoint,body){return page.evaluate(async({endpoint,body})=>api(endpoint,body),{endpoint,body});}
async function postForm(page,form,endpoint){
 const result=page.waitForResponse(r=>r.url().endsWith('/api'+endpoint)&&r.request().method()==='POST');
 await page.locator(form).locator('button.primary').click();const response=await result;
 const value=await response.json();assert(response.ok(),JSON.stringify(value));return value;
}
async function ready(page){await page.waitForFunction(()=>appReady);}
async function view(page,name){await page.locator('[data-view="'+name+'"]').click();}
async function selectMonth(page,month){await page.locator('#statement-month').selectOption(month);await page.waitForFunction(m=>document.querySelector('#transaction-count').textContent.startsWith(m)&&document.querySelector('#transaction-panel').getAttribute('aria-busy')==='false',month);}
async function rowIds(page){return page.locator('#transaction-rows tr[data-transaction-id]').evaluateAll(rows=>rows.map(r=>r.dataset.transactionId));}
async function allRows(page){
 const ids=[];
 do{ids.push(...await rowIds(page));if(await page.locator('#tx-next').isDisabled())break;await page.locator('#tx-next').click();}while(true);
 return ids;
}
async function confirmAction(page,action){
 const card=page.locator('.action[data-action-id="'+action.id+'"]');await card.waitFor({state:'visible'});
 const button=card.getByRole('button',{name:action.kind==='QUERY_MANDATES'?'确认并查询':'确认并执行',exact:true});
 assert(await button.isDisabled());await card.locator('.verification input').check();assert(await button.isEnabled());
 const result=page.waitForResponse(r=>r.url().endsWith('/api/actions/'+action.id+'/submit'));
 await button.click();const response=await result;assert(response.ok());const receipt=await response.json();
 assert.equal(receipt.status,'VERIFIED_SUCCESS');
 await page.waitForFunction(id=>document.querySelector('.action[data-action-id="'+id+'"]')?.dataset.status==='VERIFIED_SUCCESS',action.id);
 return receipt;
}
async function confirmCard(page,preview){
 await page.locator('#card-explicit-confirm').waitFor();assert(await page.locator('#card-confirm-button').isDisabled());
 await page.locator('#card-explicit-confirm').check();
 const result=page.waitForResponse(r=>r.url().endsWith('/api/cards/operations/'+preview.id+'/confirm'));
 await page.locator('#card-confirm-button').click();const response=await result;assert(response.ok());
 const receipt=await response.json();assert.equal(receipt.status,'COMPLETED');
 await page.waitForFunction(receiptId=>document.querySelector('#card-preview').textContent.includes(receiptId),receipt.receiptId);
 await page.waitForFunction(()=>!document.querySelector('#card-explicit-confirm'));
  await page.waitForFunction(card=>document.querySelector('.bank-card[data-card-id="'+card.id+'"]')?.dataset.cardVersion===String(card.version),receipt.card);
 return receipt;
}
async function changeCard(page,cardId,kind,edit=async()=>{}){
 const card=page.locator('.bank-card[data-card-id="'+cardId+'"]');
 await card.getByRole('button',{name:kind==='LOCK'?'锁卡':kind==='UNLOCK'?'解锁':kind==='REPORT_LOST'?'挂失':'设置',exact:true}).click();
 await page.locator('#card-kind').selectOption(kind);await edit();
 return confirmCard(page,await postForm(page,'#card-settings-form','/cards/preview'));
}
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage();const errors=[];const serverErrors=[];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('response',r=>{if(r.status()>=500)serverErrors.push(r.url());});
 await page.route('**/api/chat',r=>{modelRequests++;return r.abort();});
 try{
  await page.goto(base);await ready(page);
  assert.equal((await call(page,'/session')).mode,'OFFLINE_DEMO','Run write acceptance only against the isolated offline bank');
  assert.equal(await page.locator('[data-view]').count(),8);
  const originalAccounts=await call(page,'/accounts');
  assert.deepEqual(await page.locator('#statement-month option').evaluateAll(o=>o.map(n=>n.value)),['2026-09','2026-08','2026-07']);
  for(const month of ['2026-07','2026-08','2026-09']){
   await selectMonth(page,month);const data=await call(page,'/statement?month='+month);
   assert((await page.locator('#statement-metrics').innerText()).includes((data.expense_minor/100).toLocaleString('en-US',{minimumFractionDigits:2})));
   assert((await page.locator('#transaction-count').innerText()).includes(data.transactions.length+' 笔'));
  }
  passed('three months, totals and standalone transaction panel');
  await selectMonth(page,'2026-08');const august=await call(page,'/statement?month=2026-08');
  for(const sort of ['date-asc','date-desc','amount-asc','amount-desc']){
   await page.locator('#tx-sort').selectOption(sort);
   const expected=[...august.transactions].sort((a,b)=>{
    const n=sort.startsWith('amount')?Math.abs(a.amount_minor)-Math.abs(b.amount_minor):a.booked_on.localeCompare(b.booked_on);
    return (sort.endsWith('desc')?-n:n)||a.id.localeCompare(b.id);
   }).map(t=>t.id);
   assert.deepEqual(await allRows(page),expected);
  }
  await page.locator('#tx-reset').click();await page.locator('#tx-from').fill('2026-08-10');await page.locator('#tx-to').fill('2026-08-26');
  await page.locator('#tx-min').fill('50');await page.locator('#tx-max').fill('300');await page.locator('#transaction-filters button[type=submit]').click();
  const filtered=august.transactions.filter(t=>t.booked_on>='2026-08-10'&&t.booked_on<='2026-08-26'&&Math.abs(t.amount_minor)>=5000&&Math.abs(t.amount_minor)<=30000);
  assert.equal((await rowIds(page)).length,filtered.length);
  assert.deepEqual(new Set(await rowIds(page)),new Set(filtered.map(t=>t.id)));
  await page.locator('#tx-min').fill('400');await page.locator('#transaction-filters button[type=submit]').click();
  assert((await page.locator('#tx-error').innerText()).includes('最低金额不能大于'));
  await page.locator('#tx-reset').click();await page.locator('#tx-from').fill('2026-08-26');await page.locator('#tx-to').fill('2026-08-10');await page.locator('#transaction-filters button[type=submit]').click();
  assert((await page.locator('#tx-error').innerText()).includes('开始日期不能晚于'));
  await page.locator('#tx-reset').click();await page.locator('#tx-search').fill('不存在的商户');assert.deepEqual(await rowIds(page),[]);
  assert((await page.locator('#transaction-rows').innerText()).includes('没有符合条件'));
  await page.locator('#tx-reset').click();await page.locator('#tx-direction').selectOption('expense');
  assert.equal((await allRows(page)).length,august.transactions.filter(t=>t.amount_minor<0).length);
  await page.locator('#tx-reset').click();await page.locator('.spend-category').first().click();assert.notEqual(await page.locator('#tx-category').inputValue(),'');
  await page.locator('#tx-reset').click();
  await page.locator('#statement-month').focus();await page.keyboard.press('Home');await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');
  await page.waitForFunction(()=>document.querySelector('#statement-month').value==='2026-08'&&document.querySelector('#transaction-panel').getAttribute('aria-busy')==='false');
  await page.screenshot({path:path.join(out,'statement-desktop.png'),fullPage:true});
  passed('date/amount sorting, pagination, combined filters, empty and invalid ranges, keyboard dropdown');
  await view(page,'users');await page.locator('#user-add').click();
  await page.locator('#user-name').fill('验收联系人');await page.locator('#user-phone').fill('13812345678');await page.locator('#user-gender').selectOption('FEMALE');
  await page.locator('#user-note').fill('家庭储蓄');await page.locator('#user-savings').check();await page.locator('#user-balance').fill('1234.56');
  const person=await postForm(page,'#user-form','/users');assert.equal(person.balanceMinor,123456);
  const contactCard=page.locator('.person-card[data-person-id="'+person.id+'"]');await contactCard.waitFor();
  await contactCard.getByRole('button',{name:'编辑资料'}).click();assert(await page.locator('#user-account').isDisabled());assert(await page.locator('#user-balance-label').isHidden());
  await page.locator('#user-name').fill('验收联系人已修改');await page.locator('#user-note').fill('家庭备用金');await postForm(page,'#user-form','/users/'+person.id);
  await page.waitForFunction(id=>document.querySelector('.person-card[data-person-id="'+id+'"]').textContent.includes('已修改'),person.id);
  const updated=(await call(page,'/users')).find(p=>p.id===person.id);assert.equal(updated.balanceMinor,123456);assert.equal(updated.gender,'FEMALE');assert(updated.savingsTarget);
  await page.locator('#profile-details summary').click();await page.locator('#profile-name').fill('资料验收');await page.locator('#profile-phone').fill('13912345678');await page.locator('#profile-gender').selectOption('MALE');await postForm(page,'#profile-form','/users/profile');
  await page.screenshot({path:path.join(out,'users-desktop.png'),fullPage:true});
  await view(page,'tools');await page.locator('#recipient-search').fill('5678');
  assert.deepEqual(await page.locator('#recipient option').evaluateAll(o=>o.map(n=>n.value)),[person.id]);
  assert((await page.locator('#recipient option').innerText()).includes('已修改'));
  await page.locator('#amount').fill('10');const transfer=await postForm(page,'#transfer-form','/transfers/preview');
  await confirmAction(page,transfer);
  await page.waitForFunction(()=>document.querySelector('#statement-month option[value="2026-10"]'));
  const afterTransfer=(await call(page,'/users')).find(p=>p.id===person.id);assert.equal(afterTransfer.balanceMinor,124456);
  await view(page,'savings');assert(await page.locator('#schedule-recipient option[value="'+person.id+'"]').count());
  await page.locator('#schedule-source').selectOption('travel');assert.equal(await page.locator('#schedule-recipient option[value="travel-recipient"]').count(),0);
  await page.locator('#schedule-source').selectOption('checking');
  await page.locator('#schedule-recipient').selectOption(person.id);const schedulePreview=await postForm(page,'#schedule-form','/schedules/preview');assert.equal(schedulePreview.recipient_id,person.id);
  passed('user create/edit/profile, readonly balances, shared recipient/target sources and transfer balance updates');
  await page.locator('#workflow-mandates button').click();
  // The intent endpoint is discovered from the generated inline confirmation, preserving the existing authorization protocol.
  await page.locator('#mandate-query-area .action').waitFor();
  const queryId=await page.locator('#mandate-query-area .action').getAttribute('data-action-id');
  const query=await call(page,'/actions/'+queryId);await confirmAction(page,query);
  const choices=page.locator('#workflow-mandates input[type=checkbox]');assert((await choices.count())>=2);
  const music=page.locator('#workflow-mandates input[value="music"]'),video=page.locator('#workflow-mandates input[value="video"]');
  assert(await music.isEnabled());await music.check();await video.check();
  assert((await page.locator('#mandate-selection-summary').innerText()).includes('已选择 2 项'));assert((await page.locator('#mandate-selection-summary').innerText()).includes('83.00'));
  await page.setViewportSize({width:390,height:844});await music.uncheck();await music.check();assert(await music.isChecked());
  await page.screenshot({path:path.join(out,'savings-mobile-selection.png'),fullPage:true});await page.setViewportSize({width:1440,height:1000});
  const workflow=await postForm(page,'#workflow-form','/workflows');let current=workflow;
  for(let step=0;step<4;step++){
   current=await call(page,'/workflows/'+workflow.id);if(current.state==='VERIFIED_SUCCESS')break;
   assert(current.pendingAction?.id);const actionId=current.pendingAction.id;await confirmAction(page,current.pendingAction);
   await page.waitForFunction(({id,old})=>workflows.some(w=>w.id===id&&(w.state==='VERIFIED_SUCCESS'||w.pendingAction?.id!==old)),{id:workflow.id,old:actionId});
  }
  current=await call(page,'/workflows/'+workflow.id);assert.equal(current.state,'VERIFIED_SUCCESS');assert.equal(current.estimatedSavingMinor,8300);
  assert((await call(page,'/schedules')).some(s=>s.amount_minor===8300));
  passed('inline mandate authorization, clickable mobile checkboxes, selection summary and complete savings workflow');
  await view(page,'cards');await page.locator('#card-apply-open').click();await page.locator('#card-label').fill('日常消费卡');
  const preview=await postForm(page,'#card-apply-form','/cards/preview');assert.deepEqual(await call(page,'/cards'),[]);
  const issued=await confirmCard(page,preview);const cardId=issued.card.id;
  await page.waitForFunction(id=>document.querySelector('.bank-card[data-card-id="'+id+'"]'),cardId);
  const repeated=await call(page,'/cards/operations/'+preview.id+'/confirm',{confirmed:true,payloadHash:preview.payloadHash});assert.equal(repeated.receiptId,issued.receiptId);assert.equal((await call(page,'/cards')).length,1);
  const limited=await changeCard(page,cardId,'UPDATE_LIMITS',async()=>{await page.locator('#card-per').fill('1000');await page.locator('#card-daily').fill('6000');});assert.equal(limited.card.dailyLimitMinor,600000);
  const controls=await changeCard(page,cardId,'UPDATE_CONTROLS',async()=>{await page.locator('#card-online').uncheck();await page.locator('#card-overseas').check();});assert(!controls.card.onlineEnabled);assert(controls.card.overseasEnabled);
  assert.equal((await changeCard(page,cardId,'LOCK')).card.status,'LOCKED');assert.equal((await changeCard(page,cardId,'UNLOCK')).card.status,'ACTIVE');
  await page.reload();await ready(page);await view(page,'cards');assert((await page.locator('.bank-card').innerText()).includes('6,000.00'));
  assert.equal((await call(page,'/users/profile')).name,'资料验收');assert((await call(page,'/users')).some(p=>p.id===person.id&&p.name==='验收联系人已修改'));
  const balancesBeforeLoss=await call(page,'/accounts');assert.equal((await changeCard(page,cardId,'REPORT_LOST')).card.status,'LOST');
  assert.deepEqual(await call(page,'/accounts'),balancesBeforeLoss);assert.equal(await page.locator('.bank-card[data-card-id="'+cardId+'"] button').count(),0);
  await page.locator('#card-apply-open').click();await page.locator('#card-label').fill('旅游备用卡');await page.locator('#card-account').selectOption('travel');await page.locator('#card-type').selectOption('VIRTUAL');
  await confirmCard(page,await postForm(page,'#card-apply-form','/cards/preview'));
  await page.waitForFunction(()=>document.querySelectorAll('.bank-card').length===2);
  await page.locator('#card-apply-open').click();await page.locator('#card-label').fill('取消的卡片');const cancelled=await postForm(page,'#card-apply-form','/cards/preview');
  await page.locator('#card-preview').getByRole('button',{name:'取消预览',exact:true}).click();await page.waitForFunction(()=>document.querySelector('#card-preview').textContent.includes('预览已取消'));
  assert.equal((await call(page,'/cards/operations/'+cancelled.id)).status,'CANCELLED');assert.equal((await call(page,'/cards')).length,2);
  await page.locator('#card-apply-close').click();await page.screenshot({path:path.join(out,'cards-desktop.png'),fullPage:true});
  passed('card preview/explicit confirmation, original receipt retry, limits, switches, lock/unlock/loss, cancel and reload');
  const validation=await page.evaluate(async()=>{
   const tests=[['/users',{name:'',phone:'bad',gender:'invalid',note:'',savingsTarget:false,initialBalanceMinor:0}],['/cards/preview',{kind:'APPLY',accountId:'other',label:'越权卡',cardType:'DEBIT',perTransactionLimitMinor:100,dailyLimitMinor:100,onlineEnabled:true,overseasEnabled:false}]];
   const result=[];
   for(const [endpoint,body] of tests){const r=await fetch('/api'+endpoint,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify(body)});result.push({status:r.status,code:(await r.json()).code});}
   const csrfResponse=await fetch('/api/users',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});result.push({status:csrfResponse.status,code:(await csrfResponse.json()).code});return result;
  });assert(validation.every(r=>r.status>=400&&r.status<500));
  passed('HTTP validation, account ownership and CSRF rejection');
  for(const width of [1440,1024,768,390]){
   await page.setViewportSize({width,height:width<600?844:1000});
   for(const name of ['statement','users','savings','cards','tools']){
    await view(page,name);
    const size=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));assert(size.scroll<=size.width+1,`${name} overflows at ${width}: ${size.scroll}`);
    if(width===390&&['statement','users','cards'].includes(name))await page.screenshot({path:path.join(out,name+'-mobile.png'),fullPage:true});
   }
  }
  passed('all management views fit 1440/1024/768/390px without page overflow');
  assert.deepEqual(errors,[]);assert.deepEqual(serverErrors,[]);assert.equal(modelRequests,0);
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({passed:true,checks,modelRequests,pageErrors:errors,serverErrors,originalAccountCount:originalAccounts.length},null,2));
  console.log(JSON.stringify({passed:true,groups:checks.length,modelRequests}));
 }catch(e){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true}).catch(()=>{});fs.writeFileSync(path.join(out,'failure.json'),JSON.stringify({checks,error:e.message,errors,serverErrors},null,2));throw e;}
 finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
