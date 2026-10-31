// Browser plugin not available. Playwright/Edge; all routes fulfilled locally, no model calls.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/context-ui-qa'),origin='http://127.0.0.1:8110';fs.mkdirSync(out,{recursive:true});
const requests=[],saved=new Map();let view=null,lost=false;
async function routes(page){await page.route(origin+'/**',async route=>{
 const req=route.request(),url=new URL(req.url());
 if(!url.pathname.startsWith('/api/')){const file=url.pathname==='/'?'index.html':url.pathname.slice(1);const target=path.join(root,'src/main/resources/static',file);if(fs.existsSync(target))return route.fulfill({contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.html')?'text/html':'image/svg+xml',body:fs.readFileSync(target)});return route.fulfill({status:404,body:''});}
 const p=url.pathname.slice(4);let data={},status=200,body={};try{body=req.postDataJSON()||{};}catch{}requests.push({method:req.method(),path:p,body});
 if(p==='/session')data={user:'synthetic-u1',csrf:'mock-csrf',mode:'OFFLINE_DEMO',model:'offline',faultsEnabled:false};
 else if(p==='/accounts')data=[{id:'checking',label:'日常账户',balance_minor:1243000},{id:'travel',label:'旅游账户',balance_minor:50000}];
 else if(p==='/recipients')data=[];
 else if(['/schedules','/workflows','/audit'].includes(p))data=[];
 else if(p==='/statement')data={month:'2026-09',expense_minor:40000,income_minor:100000,internal_transfer_minor:0,categories:{维修:40000},transactions:[{id:'repair',merchant:'维修店',amount_minor:-40000,booked_on:'2026-09-03'}],anomaly_candidates:[]};
 else if(p==='/chat'){
  if(saved.has(body.requestId))data=saved.get(body.requestId);
  else if(view&&body.expectedContextVersion!==undefined&&body.expectedContextVersion!==view.version){status=409;data={code:'CONFLICT',message:'对话版本已改变'};}
  else{view={conversationId:'mock-context',version:(view?.version||1)+1,intent:'STATEMENT_ANALYSIS',state:'ACTIVE',slots:{primary_month:'2026-09'},bankReferences:[],displayHistory:[...(view?.displayHistory||[]),{user:body.text,assistant:'本轮只核对相关账单记录。'}].slice(-4)};data={text:'本轮只核对相关账单记录。',mode:'OFFLINE_DEMO',conversationId:view.conversationId,contextVersion:view.version,context:view,clarification:null,actions:[],results:[],data:{},trace:[]};saved.set(body.requestId,data);if(body.text.includes('断线')&&!lost){lost=true;return route.abort();}}
 }else if(p==='/conversations/mock-context')data=view;
 else if(p==='/conversations/mock-context/context/reset'){view={...view,version:view.version+1,intent:'GENERAL',slots:{},displayHistory:[]};data=view;}
 else {status=404;data={code:'NOT_FOUND',message:'mock route not found'};}
 await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
});}
async function send(page,text){await page.locator('#prompt').fill(text);await page.locator('#chat-form button[type=submit]').click();await page.waitForFunction(()=>document.querySelector('#chat-form').getAttribute('aria-busy')==='false');}
(async()=>{const browser=await chromium.launch({channel:'msedge',headless:true});const errors=[];try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}});page.on('pageerror',e=>errors.push(e.message));await routes(page);await page.goto(origin);await page.waitForFunction(()=>document.querySelector('#mode').textContent!=='正在建立会话');
 await send(page,'分析2026-09账单');await page.locator('#context-summary').filter({hasText:'2026-09'}).waitFor();
 let chatCount=requests.filter(r=>r.path==='/chat').length;await page.reload();await page.locator('#context-summary').filter({hasText:'2026-09'}).waitFor();assert.equal(requests.filter(r=>r.path==='/chat').length,chatCount,'refresh must not call model/chat');
 await send(page,'模拟断线后核对维修');await page.locator('#chat-retry').waitFor({state:'visible'});const original=requests.filter(r=>r.path==='/chat').at(-1).body;await page.locator('#chat-retry').click();await page.waitForFunction(()=>document.querySelector('#chat-form').getAttribute('aria-busy')==='false');assert.deepEqual(requests.filter(r=>r.path==='/chat').at(-1).body,original,'retry must reuse exact original request');
 const second=await browser.newPage();second.on('pageerror',e=>errors.push(e.message));await routes(second);await second.addInitScript(value=>{sessionStorage.setItem('safebank.contextUser',JSON.stringify('synthetic-u1'));sessionStorage.setItem('safebank.context',JSON.stringify(value));},{conversationId:view.conversationId,version:view.version});await second.goto(origin);await second.locator('#context-summary').filter({hasText:'2026-09'}).waitFor();
 await send(page,'再核对一项');chatCount=requests.filter(r=>r.path==='/chat').length;await send(second,'旧标签页继续');assert.equal(requests.filter(r=>r.path==='/chat').length,chatCount+1,'version conflict must not auto-resend');assert.match(await second.locator('#chat-status').textContent(),/版本|最新/);
 await page.screenshot({path:path.join(out,'desktop-1440.png'),fullPage:true});await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'mobile-390.png'),fullPage:true});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'mobile overflow');assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({scope:'mock browser contracts; backend API separately verified',checks:['refresh without chat','exact retry request','two-tab conflict','desktop/mobile','no page errors'],modelRequests:0,chatPosts:requests.filter(r=>r.path==='/chat').length,pageErrors:errors},null,2));process.stdout.write('context browser contracts passed; model requests 0\n');
}finally{await browser.close();}})().catch(e=>{process.stderr.write(e.stack+'\n');process.exitCode=1;});
