// Local UI regression. Chat is intercepted: no provider requests or bank writes.
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];let calls=0,fail=false,failRefresh=false;
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async route=>{
  const url=new URL(route.request().url());
  const file={'/':'index.html','/app.js':'app.js','/styles.css':'styles.css','/theme.css':'theme.css','/motion.js':'motion.js'}[url.pathname];
  if(file&&process.env.SAFEBANK_UI_LIVE!=='1')return route.fulfill({body:fs.readFileSync(path.join('src/main/resources/static',file)),contentType:file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html'});
  if(url.pathname==='/api/chat'){
   calls++;await new Promise(r=>setTimeout(r,150));
   if(fail)return route.fulfill({status:503,json:{message:'本地测试：服务暂时不可用'}});
   if(failRefresh)return route.fulfill({json:{text:'本地模拟：任务已经创建。',mode:'CONFIGURED',data:{workflow:{id:'mock-workflow'}},results:[],actions:[],trace:[]}});
   return route.fulfill({json:{text:'本地模拟回答：请结合实际用途核对大额支出。',mode:'CONFIGURED',data:{month:'2026-09',income_minor:1170000,expense_minor:536933,categories:{居住:250000,维修:200000,日用品:36350,餐饮:18424,其他:32159},transactions:[{booked_on:'2026-09-01',merchant:'租金（合成）',amount_minor:-250000},{booked_on:'2026-09-10',merchant:'设备维修（合成）',amount_minor:-200000}],anomaly_candidates:[]},results:[],actions:[],trace:[]}});
  }
  if(failRefresh&&url.pathname==='/api/accounts')return route.fulfill({status:503,json:{message:'本地测试：账户刷新失败'}});
  if(route.request().method()!=='GET')throw Error('Unexpected write '+url.pathname);
  return route.continue();
 });
 try{
  await page.goto(process.env.SAFEBANK_UI_BASE_URL||'http://127.0.0.1:8093/');await page.locator('#accounts article').first().waitFor({state:'attached'});
  assert.equal(await page.locator('#chat-form .primary').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(58, 94, 207)','Blue primary theme must load');
  assert.equal(await page.locator('[data-view="statement"]').evaluate(el=>getComputedStyle(el).borderLeftColor),'rgb(216, 12, 24)','Active navigation uses a red accent');
  const desktopBusiness=await page.locator('.business-content').boundingBox(),desktopChat=await page.locator('.chat-panel').boundingBox();
  assert(desktopChat.x>=desktopBusiness.x+desktopBusiness.width,'Desktop chat must remain on the right');
  assert.equal(await page.locator('[data-view]').count(),6,'Left navigation must expose six business views');
  await page.locator('[data-view="mandates"]').click();assert(await page.locator('#mandates').isVisible());
  assert(!(await page.locator('#transfer-form').isVisible()));
  for(const [key,selector] of [['chat','#focus-chat'],['savings','#schedule-form'],['tools','#transfer-form'],['audit','#simulate-form']]){await page.locator('[data-view="'+key+'"]').click();assert(await page.locator(selector).isVisible());}
  await page.locator('#accounts-toggle').click();assert(await page.locator('#accounts').isVisible());await page.locator('#accounts-toggle').click();
  await page.locator('[data-view="statement"]').click();assert(await page.locator('#tool-result').isVisible());
  await page.locator('#prompt').fill('分析2026-09账单');await page.locator('#prompt').press('Enter');
  await page.locator('#chat-status').filter({hasText:'正在'}).waitFor();
  await page.locator('#messages').getByText('本地模拟回答：请结合实际用途核对大额支出。').waitFor();
  fs.mkdirSync('target/ui-qa',{recursive:true});await page.screenshot({path:'target/ui-qa/workspace-desktop.png',fullPage:true});
  assert.equal(calls,1);await page.locator('#prompt').fill('核对');await page.locator('#prompt').press('Shift+Enter');
  assert.equal(calls,1);assert((await page.locator('#prompt').inputValue()).includes('\n'));
  await page.locator('#prompt').evaluate(el=>el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true})));assert.equal(calls,1);
  fail=true;await page.locator('#prompt').fill('失败测试');await page.locator('#chat-form button[type="submit"]').click();
  await page.locator('#chat-retry').waitFor({state:'visible'});assert.equal(calls,2);
  fail=false;await page.locator('#chat-retry').click();await page.waitForFunction(()=>document.querySelectorAll('#messages .bubble.assistant').length>=3);assert.equal(calls,3);
  for(const width of [390,768,1024]){
   await page.setViewportSize({width,height:844});
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Horizontal overflow at '+width);
   assert(await page.locator('#chat-form').isVisible());
   const business=await page.locator('.business-content').boundingBox(),chat=await page.locator('.chat-panel').boundingBox();
   if(width<=900)assert(chat.y>=business.y+business.height,'Narrow-screen chat must follow business content');
   else assert(chat.x>=business.x+business.width,'Wide-screen chat must remain on the right');
   const row=page.locator('.category-row').first();const label=await row.locator('span').boundingBox();const value=await row.locator('strong').boundingBox();assert(label.x+label.width<=value.x,'Category label overlaps amount at '+width);
   if(width===390)await page.screenshot({path:'target/ui-qa/workspace-mobile.png',fullPage:true});
  }
  assert.deepEqual(errors,[]);
  failRefresh=true;await page.locator('#prompt').fill('创建模拟任务');await page.locator('#chat-form button[type="submit"]').click();
  await page.locator('#chat-status').filter({hasText:'页面更新失败'}).waitFor();assert.equal(calls,4);assert(!(await page.locator('#chat-retry').isVisible()),'Successful chat must not be resent after refresh fails');
  await page.emulateMedia({reducedMotion:'reduce'});
  assert(await page.locator('[data-reveal]').evaluateAll(nodes=>nodes.every(n=>getComputedStyle(n).animationName==='none')),'Reduced motion must immediately disable reveals');
  console.log(JSON.stringify({passed:true,mockedChatRequests:calls,realModelRequests:0,widths:[1440,390,768,1024],successRefreshFailureDoesNotResend:true}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
