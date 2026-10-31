// Run only on a disposable, migrated MySQL clone. Does not call any model endpoint.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const base=process.env.SAFEBANK_MYSQL_QA_URL||'http://127.0.0.1:8137';
const report=path.resolve(__dirname,'../target/workspace-qa/mysql-persistence.json');
let cookie='',csrf='';
async function request(endpoint,body){
 assert(!endpoint.includes('chat'),'Model calls are forbidden in this fixture');
 const response=await fetch(base+'/api'+endpoint,{method:body===undefined?'GET':'POST',headers:{Cookie:cookie,'X-CSRF-Token':csrf,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const sessionCookie=response.headers.get('set-cookie');if(sessionCookie)cookie=sessionCookie.split(';')[0];
 const value=await response.json();return {ok:response.ok,status:response.status,value};
}
async function call(endpoint,body){const response=await request(endpoint,body);assert(response.ok,JSON.stringify(response.value));return response.value;}
async function confirm(preview){return call('/cards/operations/'+preview.id+'/confirm',{confirmed:true,payloadHash:preview.payloadHash});}
async function change(cardId,kind,extra={}){return confirm(await call('/cards/preview',{kind,cardId,...extra}));}
(async()=>{
 const session=await call('/session');assert.equal(session.mode,'OFFLINE_DEMO','Only the isolated offline app may run write QA');csrf=session.csrf;
 if(process.argv.includes('--verify-persistence')){
  const saved=JSON.parse(fs.readFileSync(report,'utf8'));
  assert.deepEqual(await call('/cards'),saved.cards);
  assert.deepEqual(await call('/users/profile'),saved.profile);
  assert.deepEqual((await call('/users')).find(p=>p.id===saved.person.id),saved.person);
  assert.deepEqual(await call('/cards/operations/'+saved.originalReceipt.id),saved.originalReceipt);
  console.log('PASS MySQL restart persistence: user profile, contact, card settings and original receipt');return;
 }
 assert.deepEqual(await call('/cards'),[],'Use a freshly migrated clone');
 const accounts=await call('/accounts');assert(accounts.length>=2);
 const months=await call('/statement/months');for(const m of ['2026-07','2026-08','2026-09'])assert(months.includes(m));
 const profile=await call('/users/profile',{name:'数据库验收用户',phone:'',gender:'UNDISCLOSED'});
 const input={name:'数据库验收联系人',phone:'TEST-PHONE-2038',gender:'FEMALE',note:'隔离副本',savingsTarget:true,initialBalanceMinor:123456};
 const created=await call('/users',input);assert.equal(created.balanceMinor,123456);
 const person=await call('/users/'+created.id,{...input,name:'已编辑联系人',accountId:created.accountId,initialBalanceMinor:0});
 assert.equal(person.name,'已编辑联系人');assert.equal(person.gender,'FEMALE');assert.equal(person.balanceMinor,123456);
 assert.equal((await call('/users')).find(p=>p.id===person.id).savingsTarget,true);
 console.log('PASS MySQL user/profile/recipient mapping and unchanged account balances');
 const application=accountId=>({kind:'APPLY',accountId,label:'数据库验收卡',cardType:'VIRTUAL',perTransactionLimitMinor:100000,dailyLimitMinor:500000,onlineEnabled:true,overseasEnabled:false});
 const preview=await call('/cards/preview',application(accounts[0].id));
 assert.equal(Date.parse(preview.expiresAt)-Date.parse(preview.createdAt),600000);
 assert(Math.abs(Date.now()-Date.parse(preview.createdAt))<30000,'MySQL timestamp must retain the correct instant');
 const originalReceipt=await confirm(preview);assert.equal(originalReceipt.card.status,'ACTIVE');assert.equal(originalReceipt.card.onlineEnabled,true);
 const id=originalReceipt.card.id;
 assert.equal((await change(id,'UPDATE_LIMITS',{perTransactionLimitMinor:20000,dailyLimitMinor:80000})).card.dailyLimitMinor,80000);
 assert.equal((await change(id,'UPDATE_CONTROLS',{onlineEnabled:false,overseasEnabled:true})).card.onlineEnabled,false);
 assert.equal((await change(id,'LOCK')).card.status,'LOCKED');
 assert.equal((await change(id,'UNLOCK')).card.status,'ACTIVE');
 assert.equal((await change(id,'REPORT_LOST')).card.status,'LOST');
 assert.deepEqual(await confirm(preview),originalReceipt,'Repeated confirmation must preserve the original immutable receipt');
 console.log('PASS MySQL card state changes, booleans, UTC timestamps and immutable repeated receipts');
 for(let i=0;i<7;i++)await confirm(await call('/cards/preview',application(accounts[i%accounts.length].id)));
 const candidates=await Promise.all(accounts.slice(0,2).map(a=>call('/cards/preview',application(a.id))));
 const responses=await Promise.all(candidates.map(p=>request('/cards/operations/'+p.id+'/confirm',{confirmed:true,payloadHash:p.payloadHash})));
 assert.equal(responses.filter(r=>r.ok).length,1,'Different-account concurrent applications must share one owner limit');
 assert.equal(responses.find(r=>!r.ok).value.code,'LIMIT_EXCEEDED');
 const cards=await call('/cards');assert.equal(cards.filter(c=>c.status!=='LOST').length,8);
 assert.deepEqual(await call('/accounts'),accounts,'Card settings must not modify account balances');
 console.log('PASS MySQL concurrent card cap across different accounts: exactly 8 active/locked cards');
 fs.mkdirSync(path.dirname(report),{recursive:true});fs.writeFileSync(report,JSON.stringify({profile,person,cards,originalReceipt},null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
