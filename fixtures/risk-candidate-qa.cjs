// Actual isolated backend and browser acceptance; never sends /chat or model requests.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/risk-candidate-qa');
const operator=JSON.parse(fs.readFileSync(path.join(root,'.superpowers/risk-demo/operator.json'),'utf8'));
const base='http://127.0.0.1:'+operator.businessPort;
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(15000);const errors=[],requests=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.url().includes('/api/'))requests.push(new URL(r.url()).pathname);});
  await page.goto(base);await page.locator('#accounts .account').first().waitFor({state:'attached'});
  const call=(url,body)=>page.evaluate(async({url,body})=>api(url,body),{url,body});
  const balance=async()=>Number((await call('/accounts')).find(a=>a.id==='checking').balance_minor);
  const initial=await balance();
  const september=await call('/statement?month=2026-09'),august=await call('/statement?month=2026-08');
  assert.equal(september.expense_minor,536933);assert.equal(august.expense_minor,654792);
  assert.equal(august.expense_minor-september.expense_minor,117859);
  await page.locator('[data-view="mandates"]').click();await page.getByRole('button',{name:'确认查询范围',exact:true}).click();
  let card=page.locator('[data-action-id]').first();const queryId=await card.getAttribute('data-action-id');
  async function confirm(id){const c=page.locator('[data-action-id="'+id+'"]');await c.getByRole('checkbox',{name:'我已核对本次参数，并明确确认操作',exact:true}).check();await c.getByRole('button',{name:'确认并执行',exact:true}).click();await page.locator('[data-action-id="'+id+'"]').getByText('已核验完成',{exact:true}).waitFor();}
  await confirm(queryId);await page.locator('#mandates .mandate').first().waitFor();
  const queryRepeat=await call('/actions/'+queryId+'/submit',{});assert.equal(queryRepeat.data,null);assert.equal(await balance(),initial);
  const first=await call('/transfers/preview',{sourceId:'checking',recipientId:'zhang-a',amountMinor:60000});
  assert.equal(first.security.riskLevel,'YELLOW');await page.evaluate(a=>showAction(a),first);await confirm(first.id);assert.equal(await balance(),initial-60000);
  const second=await call('/transfers/preview',{sourceId:'checking',recipientId:'zhang-a',amountMinor:60000});
  assert.equal(second.security.riskLevel,'RED');assert.equal(second.security.effectiveMinor,60000);await page.evaluate(a=>showAction(a),second);card=page.locator('[data-action-id="'+second.id+'"]');
  await card.getByRole('checkbox',{name:'我已核对本次参数，并明确确认操作',exact:true}).check();assert(await card.getByRole('button',{name:'确认并执行',exact:true}).isDisabled());
  await card.getByRole('button',{name:'发起验证',exact:true}).click();await card.getByText('挑战编号',{exact:false}).waitFor();
  const statusText=await card.locator('.risk-verification [role="status"]').textContent();const challengeId=statusText.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)[0];
  const delivery=await fetch('http://127.0.0.1:'+operator.operatorPort+'/challenges/'+challengeId,{headers:{Authorization:'Bearer '+operator.operatorToken}});
  assert.equal(delivery.status,200);const delivered=await delivery.json();
  await card.getByLabel('一次性验证码').fill(delivered.code);await card.getByRole('button',{name:'校验验证码',exact:true}).click();await card.getByText('验证已通过。尚未授权或执行，请再次核对并勾选确认。',{exact:true}).waitFor();
  assert.equal(await balance(),initial-60000);assert(await card.getByRole('button',{name:'确认并执行',exact:true}).isDisabled());
  await confirm(second.id);const paid=await call('/actions/'+second.id+'/submit',{});assert.equal(paid.status,'VERIFIED_SUCCESS');assert.equal(await balance(),initial-120000);
  const revoke=await call('/mandates/preview',{mandateId:'music'});await page.evaluate(a=>showAction(a),revoke);await confirm(revoke.id);assert.equal(await balance(),initial-120000);
  const schedule=await call('/schedules/preview',{sourceId:'checking',recipientId:'travel-recipient',amountMinor:8300,firstDate:'2026-11-20',endDate:'2027-10-20',dayOfMonth:20});
  await page.evaluate(a=>showAction(a),schedule);await confirm(schedule.id);assert.equal(await balance(),initial-120000);
  const due=await call('/simulation/run-due',{date:'2026-11-20'});assert.equal(due.length,1);assert.equal(due[0].status,'VERIFIED_SUCCESS');
  const dueRepeat=await call('/simulation/run-due',{date:'2026-11-20'});assert.equal(dueRepeat[0].id,due[0].id);assert.equal(await balance(),initial-128300);
  await page.evaluate(async()=>refresh());const sum=await call('/risk/summary');assert.equal(sum.businessDate,new Date().toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'}));assert.equal(sum.committedMinor,120000);
  const geometry=await page.evaluate(()=>{const side=document.querySelector('.sidebar').getBoundingClientRect(),business=document.querySelector('#business-content').getBoundingClientRect(),chat=document.querySelector('.chat-panel').getBoundingClientRect();return {side:side.x,business:business.x,chat:chat.x};});assert(geometry.side<geometry.business&&geometry.business<geometry.chat);
  for(const width of [1440,1024,768,390]){await page.setViewportSize({width,height:1000});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));if(width===390){const below=await page.evaluate(()=>document.querySelector('.chat-panel').getBoundingClientRect().top>=document.querySelector('#business-content').getBoundingClientRect().bottom-1);assert(below);}await page.screenshot({path:path.join(out,'workspace-'+width+'.png'),fullPage:true});}
  assert.equal(errors.length,0,errors.join(';'));assert(!requests.includes('/api/chat'));assert(!requests.includes('/api/mandates'),'no unconfirmed mandate list requests');
  fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({passed:true,scope:'actual Java/MyBatis isolated synthetic backend + browser',modelRequests:0,initialBalance:initial,finalBalance:await balance(),checks:['bill comparison','explicit query and repeat receipt','600+600 red step-up','verified proof still requires confirmation','single debit on replay','revoke leaves balance unchanged','schedule creates scope without debit','due occurrence executes once','left/business/right and mobile below','four widths','no page errors'],jarSha256:operator.jarSha256,browserFallback:'Browser plugin not available'},null,2));
  console.log('Actual candidate passed: three business scenarios, 600+600 step-up, 4 widths, 0 model requests.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
