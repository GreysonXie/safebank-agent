// Real UI + HTTP acceptance on disposable local synthetic banks. No model calls.
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {spawn}=require('node:child_process');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/business-qa');
const base='http://127.0.0.1:8097';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
fs.mkdirSync(out,{recursive:true});
const jar=path.join(out,'qa.jar');fs.copyFileSync(path.join(root,'target/safebank-agent-0.4.1.jar'),jar);
async function start(name){
 try{await fetch(base+'/api/session',{signal:AbortSignal.timeout(1000)});throw Error('8097 is occupied; no existing process will be stopped');}
 catch(e){if(e.message.includes('occupied'))throw e;}
 const log=fs.openSync(path.join(out,name+'.log'),'a');
 const child=spawn('C:/Program Files/Java/jdk-21/bin/java.exe',['-Xmx192m','-Dfile.encoding=UTF-8','-jar',jar,'--server.port=8097','--spring.datasource.url=jdbc:h2:mem:acceptance-'+name+';DB_CLOSE_DELAY=-1','--bank.model.api-key=','--bank.simulation.faults-enabled=true'],{cwd:root,windowsHide:true,stdio:['ignore',log,log]});
 fs.closeSync(log);
 try{
  for(let i=0;i<45;i++){
   if(child.exitCode!==null)throw Error('Synthetic bank exited during startup');
   try{const r=await fetch(base+'/api/session');if(r.ok){assert.equal((await r.json()).mode,'OFFLINE_DEMO');return child;}}catch(e){if(e.code==='ERR_ASSERTION')throw e;}
   await delay(1000);
  }
  throw Error('Synthetic bank startup timed out');
 }catch(e){child.kill();throw e;}
}
async function stop(child){
 if(child.exitCode!==null||child.signalCode!==null)return;
 child.kill();
 for(let i=0;i<20&&child.exitCode===null&&child.signalCode===null;i++)await delay(250);
 assert(child.exitCode!==null||child.signalCode!==null,'Owned disposable bank did not stop');
}
async function call(page,endpoint,body){return page.evaluate(async({endpoint,body})=>api(endpoint,body),{endpoint,body});}
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});const records=[];
 try{
  for(const scenario of ['normal','partial','unknown']){
   const child=await start(scenario);const context=await browser.newContext({viewport:{width:1440,height:1000}});const page=await context.newPage();const errors=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/api/chat',route=>{errors.push('Unexpected model chat request');return route.abort();});
   try{
    await page.goto(base);await page.locator('#accounts article').first().waitFor({state:'attached'});
    const initial=await call(page,'/accounts');const bill=await call(page,'/statement?month=2026-09');assert.equal(bill.expense_minor,340300);
    await page.evaluate(statement=>showData(statement,[]),bill);
    await page.locator('[data-view="savings"]').click();
    await page.locator('#workflow-mandates input[value="music"]').check();await page.locator('#workflow-mandates input[value="video"]').check();
    const created=page.waitForResponse(r=>r.url().endsWith('/api/workflows')&&r.request().method()==='POST');
    await page.locator('#workflow-form button').click();const first=await (await created).json();
    await page.waitForFunction(id=>workflows.some(w=>w.id===id),first.id);
    let task=await call(page,'/workflows/'+first.id),unknownId=null;
    while(!['VERIFIED_SUCCESS','PARTIAL_SUCCESS'].includes(task.state)){
     const action=task.pendingAction;assert(action.id,'Expected a pending action');
     const fault=scenario==='partial'&&action.mandate_id==='video'?'BEFORE_SUBMIT_FAILURE':scenario==='unknown'&&!unknownId?'AFTER_COMMIT_RESPONSE_LOST':'NONE';
     if(fault!=='NONE')await call(page,'/simulation/faults',{actionId:action.id,submitMode:fault,lookupMode:scenario==='unknown'?'LOOKUP_UNAVAILABLE':'NONE'});
     const card=page.locator('.action[data-action-id="'+action.id+'"]');
     await card.waitFor();const confirm=card.getByRole('button',{name:'确认并执行',exact:true});assert(await confirm.isDisabled());
     await card.locator('.verification input').check();
     const submitted=page.waitForResponse(r=>r.url().endsWith('/api/actions/'+action.id+'/submit'));
     await confirm.click();const receipt=await (await submitted).json();
     await page.waitForFunction(({id,actionId})=>workflows.some(w=>w.id===id&&(w.pendingAction.id!==actionId||w.state==='UNKNOWN')),{id:task.id,actionId:action.id});
     task=await call(page,'/workflows/'+task.id);
     if(scenario==='unknown'&&!unknownId){
      unknownId=action.id;assert.equal(receipt.status,'UNKNOWN');assert.equal(task.state,'UNKNOWN');assert.equal(task.estimatedSavingMinor,0);assert.equal(task.steps.length,1);
      await page.screenshot({path:path.join(out,'unknown-paused.png'),fullPage:true});
      await page.reload();await page.locator('#accounts article').first().waitFor({state:'attached'});
      task=await call(page,'/workflows/'+task.id);assert.equal(task.pendingAction.id,unknownId);assert.equal(task.steps.length,1);
      await call(page,'/simulation/faults',{actionId:unknownId,submitMode:'AFTER_COMMIT_RESPONSE_LOST',lookupMode:'NONE'});
      await page.locator('[data-view="savings"]').click();
      await page.locator('.action[data-action-id="'+unknownId+'"]').getByRole('button',{name:'查询最新状态',exact:true}).click();
      await page.waitForFunction(({id,old})=>workflows.some(w=>w.id===id&&w.pendingAction.id!==old),{id:task.id,old:unknownId});
      task=await call(page,'/workflows/'+task.id);assert.equal(task.estimatedSavingMinor,1500);
      const audit=await call(page,'/audit');assert.equal(audit.filter(e=>e.action_id===unknownId&&e.event==='EXECUTED').length,1);
     }
    }
    const saving=scenario==='partial'?1500:8300;assert.equal(task.estimatedSavingMinor,saving);assert.equal(task.state,scenario==='partial'?'PARTIAL_SUCCESS':'VERIFIED_SUCCESS');
    assert.deepEqual(await call(page,'/accounts'),initial,'Revocation and plan registration must not move funds');
    await page.reload();await page.locator('#accounts article').first().waitFor({state:'attached'});
    const restored=await call(page,'/workflows/'+task.id);assert.equal(restored.state,task.state);assert.equal(restored.version,task.version);
    const schedules=await call(page,'/schedules');assert.equal(schedules.length,1);assert.equal(schedules[0].amount_minor,saving);
    const due=await call(page,'/simulation/run-due',{date:'2026-11-20'});const repeated=await call(page,'/simulation/run-due',{date:'2026-11-20'});
    assert.equal(due[0].status,'VERIFIED_SUCCESS');assert.equal(repeated[0].id,due[0].id);
    const after=await call(page,'/accounts');assert.equal(after.find(a=>a.id==='checking').balance_minor,initial.find(a=>a.id==='checking').balance_minor-saving);
    assert.equal(after.find(a=>a.id==='travel').balance_minor,initial.find(a=>a.id==='travel').balance_minor+saving);
    const audit=await call(page,'/audit');assert.equal(audit.filter(e=>e.action_id===due[0].id&&e.event==='EXECUTED').length,1);
    await page.evaluate(async()=>{await refresh();await restoreActions();});await page.locator('[data-view="savings"]').click();
    await page.screenshot({path:path.join(out,scenario+'-complete.png'),fullPage:true});assert.deepEqual(errors,[]);
    records.push({scenario,passed:true,state:task.state,savingMinor:saving,workflowId:task.id,refreshPreservesProgress:true,unknownOriginalActionId:unknownId,scheduledActionId:due[0].id,repeatedDueReusesAction:true,balancesVerified:true});
   }finally{await context.close();await stop(child);}
  }
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({scope:'real browser and synthetic-bank HTTP; three isolated disposable databases; no model calls',passed:records.length===3,modelRequests:0,records},null,2));
  console.log(JSON.stringify({passed:true,scenarios:records.map(r=>r.scenario),modelRequests:0}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
