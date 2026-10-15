// One bounded acceptance pass on a disposable six-month bank; no provider calls.
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/demo-candidate-qa');
const base='http://127.0.0.1:8097',delay=ms=>new Promise(r=>setTimeout(r,ms));
async function api(page,endpoint,body){return page.evaluate(async({endpoint,body})=>api(endpoint,body),{endpoint,body});}
(async()=>{
 fs.mkdirSync(out,{recursive:true});
 const occupied=await fetch(base+'/api/session',{signal:AbortSignal.timeout(1000)}).then(()=>true,()=>false);
 assert(!occupied,'8097 occupied; existing processes will not be touched');
 const log=fs.openSync(path.join(out,'bank.log'),'w');
 const child=spawn('C:/Program Files/Java/jdk-21/bin/java.exe',['-Xmx192m','-Dfile.encoding=UTF-8','-jar',path.join(root,'target/safebank-agent-0.4.1.jar'),
  '--server.port=8097','--spring.datasource.url=jdbc:h2:mem:demo-candidate;DB_CLOSE_DELAY=-1',
  '--spring.sql.init.data-locations=file:fixtures/v03/v03-synthetic-bank.sql','--bank.model.api-key=','--bank.simulation.faults-enabled=false'],
  {cwd:root,windowsHide:true,stdio:['ignore',log,log]});fs.closeSync(log);
 let browser;
 try{
  let ready=false;
  for(let i=0;i<35;i++){
   assert.equal(child.exitCode,null,'Disposable bank exited');
   const session=await fetch(base+'/api/session').then(r=>r.ok?r.json():null,()=>null);
   if(session){assert.equal(session.mode,'OFFLINE_DEMO');ready=true;break;}await delay(1000);
  }
  assert(ready,'Startup timed out');
  browser=await chromium.launch({channel:'msedge',headless:true});
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
  let modelRequests=0;
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/chat',route=>{modelRequests++;return route.abort();});
  await page.goto(base);await page.locator('#accounts article').first().waitFor({state:'attached'});
  const initial=await api(page,'/accounts');
  const september=await api(page,'/statement?month=2026-09'),august=await api(page,'/statement?month=2026-08');
  assert.equal(september.expense_minor,536933);assert.equal(august.expense_minor,654792);
  assert.equal(august.expense_minor-september.expense_minor,117859);
  const repair=september.transactions.filter(t=>t.category==='维修');assert.equal(repair.length,1);assert.equal(repair[0].amount_minor,-200000);
  await page.evaluate(statement=>showData(statement,[]),september);
  assert((await page.locator('#tool-result').innerText()).includes('5,369.33'));
  await page.screenshot({path:path.join(out,'statement.png'),fullPage:true});
  assert.deepEqual(await api(page,'/accounts'),initial);

  await page.locator('[data-view="mandates"]').click();
  const previewResponse=page.waitForResponse(r=>r.url().endsWith('/api/mandates/preview'));
  await page.locator('#mandates .mandate').filter({hasText:'音乐服务（合成）'}).getByRole('button',{name:'预览撤销'}).click();
  const revoke=await (await previewResponse).json();assert.equal(revoke.status,'AWAITING_AUTH');
  assert.deepEqual(await api(page,'/accounts'),initial);
  async function confirm(action){
   const card=page.locator('.action[data-action-id="'+action.id+'"]');await card.waitFor();
   const button=card.getByRole('button',{name:'确认并执行',exact:true});assert(await button.isDisabled());
   await card.locator('.verification input').check();
   const response=page.waitForResponse(r=>r.url().endsWith('/api/actions/'+action.id+'/submit'));
   await button.click();const receipt=await (await response).json();assert.equal(receipt.status,'VERIFIED_SUCCESS');
   await page.waitForFunction(id=>document.querySelector('.action[data-action-id="'+id+'"]')?.innerText.includes('已核验完成'),action.id);
   return receipt;
  }
  await confirm(revoke);
  assert.equal((await api(page,'/mandates')).find(m=>m.id==='music').status,'REVOKED');
  assert.deepEqual(await api(page,'/accounts'),initial);
  await page.screenshot({path:path.join(out,'revoke.png'),fullPage:true});

  await page.locator('[data-view="tools"]').click();await page.locator('#recipient').selectOption('zhang-a');await page.locator('#amount').fill('500.00');
  const transferResponse=page.waitForResponse(r=>r.url().endsWith('/api/transfers/preview'));
  await page.locator('#transfer-form button').click();const transfer=await (await transferResponse).json();
  assert.equal(transfer.status,'AWAITING_AUTH');assert.equal(transfer.amount_minor,50000);assert.equal(transfer.recipient_id,'zhang-a');
  assert.deepEqual(await api(page,'/accounts'),initial);
  await confirm(transfer);
  const after=await api(page,'/accounts');
  assert.equal(after.find(a=>a.id==='checking').balance_minor,initial.find(a=>a.id==='checking').balance_minor-50000);
  await api(page,'/actions/'+transfer.id+'/submit',{});assert.deepEqual(await api(page,'/accounts'),after);
  await page.reload();await page.locator('#accounts article').first().waitFor({state:'attached'});
  assert.equal((await api(page,'/actions/'+transfer.id)).status,'VERIFIED_SUCCESS');
  const audit=await api(page,'/audit');assert.equal(audit.filter(e=>e.action_id===transfer.id&&e.event==='EXECUTED').length,1);
  await page.screenshot({path:path.join(out,'transfer.png'),fullPage:true});
  assert.equal(modelRequests,0);assert.deepEqual(errors,[]);
  const report={passed:true,scope:'real browser + isolated six-month synthetic bank; no model conversation',modelRequests:0,
   records:[{scene:'statement-and-repair-evidence',passed:true,septemberExpenseMinor:536933,augustExpenseMinor:654792,differenceMinor:117859,repairMinor:200000,liveConversationVerified:false},
    {scene:'mandate-query-preview-authorize-revoke',passed:true,balancesUnchanged:true},
    {scene:'transfer-preview-authorize-submit-restore',passed:true,amountMinor:50000,duplicateSubmitMovesMoneyOnce:true,refreshRestoresReceipt:true}],
   browserPlugin:'not available; regular Playwright used'};
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{
  if(browser)await browser.close();
  if(child.exitCode===null&&child.signalCode===null){child.kill();for(let i=0;i<20&&child.exitCode===null&&child.signalCode===null;i++)await delay(250);}
  assert(child.exitCode!==null||child.signalCode!==null,'Disposable bank did not stop');
 }
})().catch(e=>{console.error(e);process.exitCode=1;});
