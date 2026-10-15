// Isolated browser contract checks. All /api requests mocked; no model calls.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/risk-ui-qa');
fs.mkdirSync(out,{recursive:true});
const now=Date.now(),secret='729418',calls=[],states=new Map();
function action(id,kind='TRANSFER',level='RED'){return {id,user_id:'u1',kind,payload_hash:'bound-hash-'+id,status:'AWAITING_AUTH',source_id:'checking',recipient_id:'zhang-a',amount_minor:150000,security:{decisionId:'decision-'+id,policyVersion:'bank-risk-v1',riskLevel:level,requiredAssurance:level==='RED'?'STEP_UP':'CONFIRMED',reasonCode:level==='RED'?'DAILY_OUTGOING_THRESHOLD_EXCEEDED':'EXPLICIT_CONFIRMATION_REQUIRED',businessDate:'2026-10-09',effectiveMinor:0,projectedMinor:150000,validUntil:new Date(now+300000).toISOString(),nextStep:level==='RED'?'VERIFY_THEN_CONFIRM':'CONFIRM',verificationMode:'LOCAL_DEMO_ONLY',needsRefresh:false}};}
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(6000);const consoleErrors=[];
  page.on('pageerror',e=>consoleErrors.push(e.message));
  await page.route('**/app.js*',route=>route.fulfill({contentType:'text/javascript',body:fs.readFileSync(path.join(root,'src/main/resources/static/app.js'),'utf8')}));
  await page.route('**/theme.css*',route=>route.fulfill({contentType:'text/css',body:fs.readFileSync(path.join(root,'src/main/resources/static/theme.css'),'utf8')}));
  await page.route('**/api/**',async route=>{
   const request=route.request(),url=new URL(request.url()),p=url.pathname.replace('/api','');calls.push(request.method()+' '+p);let data={},status=200;let body={};try{body=request.postDataJSON()||{};}catch{}
   if(p==='/session')data={csrf:'synthetic-csrf',mode:'OFFLINE_DEMO',model:'offline',faultsEnabled:false};
   else if(p==='/accounts')data=[{id:'checking',label:'日常账户',balance_minor:1243000},{id:'travel',label:'旅游账户',balance_minor:50000}];
   else if(['/schedules','/workflows','/audit'].includes(p))data=[];
   else if(p==='/mandates'){status=409;data={code:'QUERY_CONFIRMATION_REQUIRED',message:'先确认查询'};}
   else if(p==='/query-intents'){data=action('query','QUERY_MANDATES','YELLOW');delete data.amount_minor;states.set(data.id,data);}
   else if(p.endsWith('/risk/preview')){const id=p.split('/')[2];data=states.get(id).security;}
   else if(p.endsWith('/verification-challenges'))data={challengeId:'challenge',expiresAt:new Date(now+120000).toISOString(),verificationMode:'LOCAL_DEMO_ONLY'};
   else if(p.endsWith('/challenge/verify')){if(body.code!==secret){status=403;data={code:'VERIFICATION_INVALID',message:'验证码错误'};}else data={verificationProofId:'challenge'};}
   else if(p.endsWith('/authorize')){const a=states.get(p.split('/')[2]);if(!body.userConfirmed||!body.decisionId||(a.security.riskLevel==='RED'&&!body.verificationProofId)){status=403;data={code:'STEP_UP_REQUIRED',message:'需要验证',security:a.security};}else{a.status='AUTHORIZED';data=a;}}
   else if(p.endsWith('/submit')){const a=states.get(p.split('/')[2]);a.status='VERIFIED_SUCCESS';a.receipt_id='receipt-'+a.id;data={...a};if(a.kind==='QUERY_MANDATES')data.data=[{id:'music',merchant:'音乐服务（合成）',amount_minor:1500,billing_interval:'MONTHLY',status:'ACTIVE'}];}
   else if(p.startsWith('/actions/'))data=states.get(p.split('/')[2]);
   await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto(process.env.RISK_QA_BASE||'http://127.0.0.1:8096/');
  await page.locator('#accounts').getByText('日常账户 · 合成账户').waitFor({state:'attached'});
  assert(!calls.includes('GET /mandates'),'initialization must not query mandates');
  await page.locator('[data-view="mandates"]').click();
  await page.getByRole('button',{name:'确认查询范围',exact:true}).click();
  let card=page.locator('[data-action-id="query"]');await card.locator('input[type="checkbox"]').check();await card.getByRole('button',{name:'确认并执行',exact:true}).click();await page.locator('#mandates .mandate').filter({hasText:'音乐服务（合成）'}).waitFor();
  const a=action('red');states.set(a.id,a);await page.evaluate(a=>showAction(a),a);card=page.locator('[data-action-id="red"]');
  await card.locator('input[type="checkbox"]').check();assert(await card.getByRole('button',{name:'确认并执行',exact:true}).isDisabled(),'RED cannot execute without proof');
  await card.getByRole('button',{name:'发起验证',exact:true}).click();await card.getByLabel('一次性验证码').fill(secret);await card.getByRole('button',{name:'校验验证码',exact:true}).click();
  assert(await card.getByRole('button',{name:'确认并执行',exact:true}).isDisabled(),'verified proof still needs fresh explicit confirmation');
  await card.locator('input[type="checkbox"]').check();await card.getByRole('button',{name:'确认并执行',exact:true}).click();await page.locator('[data-action-id="red"]').getByText('已核验完成',{exact:true}).waitFor();
  // Never capture verification input or private operator data.
  for(const width of [1440,1024,768,390]){
   await page.setViewportSize({width,height:1000});
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'horizontal overflow at '+width);
   await page.screenshot({path:path.join(out,'workspace-'+width+'.png'),fullPage:true});
  }
  assert.equal(consoleErrors.length,0,consoleErrors.join(';'));assert.equal(calls.filter(p=>p==='POST /actions/red/submit').length,1);
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({passed:true,scope:'mocked browser contracts',checks:['initialization','explicit query','RED proof','post-verification confirmation','one submit','four widths','no page errors'],modelRequests:0,browserFallback:'Browser plugin not available'},null,2));
  console.log('Browser contracts passed; 4 widths; 0 model requests.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
