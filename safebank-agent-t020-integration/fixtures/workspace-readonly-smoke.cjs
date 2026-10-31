// Read-only smoke for the running local app. Blocks every API mutation and model call.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const base=process.env.SAFEBANK_SMOKE_URL||'http://127.0.0.1:8134';
const out=path.resolve(__dirname,'../target/workspace-qa');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 const errors=[],serverErrors=[],blocked=[];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('response',r=>{if(r.status()>=500)serverErrors.push(new URL(r.url()).pathname);});
 await page.route('**/api/**',route=>{
  const request=route.request();
  if(request.method()!=='GET'||new URL(request.url()).pathname==='/api/chat'){
   blocked.push(request.method()+' '+new URL(request.url()).pathname);return route.abort();
  }
  return route.continue();
 });
 try{
  await page.goto(base);await page.waitForFunction(()=>appReady);
  const months=await page.locator('#statement-month option').evaluateAll(nodes=>nodes.map(n=>n.value));
  assert.equal(await page.locator('[data-view]').count(),8);
  for(const month of ['2026-07','2026-08','2026-09']){
   assert(months.includes(month));await page.locator('#statement-month').selectOption(month);
   await page.waitForFunction(m=>document.querySelector('#transaction-count').textContent.startsWith(m)&&document.querySelector('#transaction-panel').getAttribute('aria-busy')==='false',month);
   assert(await page.locator('#transaction-rows tr[data-transaction-id]').count()>0);
  }
  await page.locator('#tx-sort').selectOption('amount-desc');
  await page.locator('#tx-direction').selectOption('expense');
  assert(await page.locator('#transaction-rows tr[data-transaction-id]').count()>0);
  await page.locator('#tx-reset').click();
  for(const view of ['chat','statement','mandates','savings','tools','users','cards','audit']){
   await page.locator('[data-view="'+view+'"]').click();assert(await page.locator('#view-'+view).isVisible());
  }
  await page.locator('[data-view="cards"]').click();await page.locator('#card-apply-open').click();
  assert(await page.locator('#card-account option').count()>0);
  await page.locator('[data-view="users"]').click();assert(await page.locator('.person-card').count()>0);
  await page.locator('[data-view="tools"]').click();assert(await page.locator('#recipient option').count()>0);
  await page.locator('[data-view="savings"]').click();assert(await page.locator('#workflow-recipient option').count()>0);
  for(const width of [1440,1024,768,390]){
   await page.setViewportSize({width,height:1000});
   for(const view of ['statement','users','cards','savings']){
    await page.locator('[data-view="'+view+'"]').click();
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),view+' overflows at '+width);
   }
  }
  assert.deepEqual(blocked,[],'No API writes or model calls should be attempted');
  assert.deepEqual(errors,[]);assert.deepEqual(serverErrors,[]);
  fs.mkdirSync(out,{recursive:true});
  fs.writeFileSync(path.join(out,'actual-readonly-smoke.json'),JSON.stringify({passed:true,months,views:8,widths:[1440,1024,768,390],apiWriteAttempts:blocked,pageErrors:errors,serverErrors},null,2));
  console.log('PASS running app: eight views, July/August/September, filters and shared options, responsive layout; zero writes/model requests/errors');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
