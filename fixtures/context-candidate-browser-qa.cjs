// Browser plugin unavailable: use installed Playwright/Edge against the packaged candidate.
// Read-only navigation and restoration. No chat, authorization or submit is sent.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/21310/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const base=process.argv[2]||'http://127.0.0.1:8110';
const restore=!process.argv.includes('--fresh');
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base))throw Error('Loopback candidate required');
const root=path.resolve(__dirname,'..'),out=path.join(root,'target/context-candidate-ui-qa');fs.mkdirSync(out,{recursive:true});
const report={base,scope:'actual packaged candidate; read-only browser',checks:[],pageErrors:[],chatPosts:0};
(async()=>{const browser=await chromium.launch({channel:'msedge',headless:true});try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 page.on('pageerror',e=>report.pageErrors.push(e.message));page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/api/chat')report.chatPosts++;});
 const debugFile=path.join(root,'target/context-deepseek-debug/result.json');
 if(restore&&fs.existsSync(debugFile)){
  const debug=JSON.parse(fs.readFileSync(debugFile,'utf8')),turn=debug.groups.flatMap(g=>g.turns).at(-1);
  if(turn?.context){const session=await (await page.request.get(base+'/api/session')).json();await page.addInitScript(({view,user})=>{sessionStorage.setItem('safebank.contextUser',JSON.stringify(user));sessionStorage.setItem('safebank.context',JSON.stringify({conversationId:view.conversationId,version:view.version}));},{view:turn.context,user:session.user});}
 }
 await page.goto(base);await page.waitForFunction(()=>document.querySelector('#mode').textContent!=='正在建立会话');
 if(restore&&fs.existsSync(debugFile)){const debug=JSON.parse(fs.readFileSync(debugFile,'utf8'));if(debug.groups.some(g=>g.turns.length)){await page.locator('#context-summary').filter({hasText:'2026-09'}).waitFor();assert.ok(await page.locator('#messages').innerText());report.checks.push('restore real saved conversation without model');}}
 if(!restore)report.restoreScope='Fresh memory candidate; no saved chat seeded. Restoration verified separately before restart.';
 for(const view of ['tools','savings','mandates','audit','statement']){await page.locator(`nav [data-view="${view}"]`).click();await page.locator('#view-'+view).waitFor({state:'visible'});}
 report.checks.push('left navigation switches real business views');
 const boxes=await Promise.all(['.sidebar','#business-content','.chat-panel'].map(s=>page.locator(s).boundingBox()));
 assert.ok(boxes[0].x<boxes[1].x&&boxes[1].x<boxes[2].x);report.checks.push('desktop left navigation / center business / right chat');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await page.screenshot({path:path.join(out,'desktop-1440.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 const mobileBusiness=await page.locator('#business-content').boundingBox(),mobileChat=await page.locator('.chat-panel').boundingBox();assert.ok(mobileChat.y>=mobileBusiness.y+mobileBusiness.height-1);
 await page.screenshot({path:path.join(out,'mobile-390.png'),fullPage:true});report.checks.push('mobile business above chat; no horizontal overflow');
 await page.reload();await page.waitForFunction(()=>document.querySelector('#mode').textContent!=='正在建立会话');assert.equal(report.chatPosts,0);assert.deepEqual(report.pageErrors,[]);report.checks.push('refresh sends zero chat calls; zero page errors');
}finally{await browser.close();}})().then(()=>{report.passed=true;}).catch(e=>{report.passed=false;report.error=e.message;process.exitCode=1;}).finally(()=>{fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(report,null,2));process.stdout.write(JSON.stringify(report)+'\n');});
