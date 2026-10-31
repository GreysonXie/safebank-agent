const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
function tab(){
 const saved=new Map();let sequence=0;
 const ctx=vm.createContext({crypto:{randomUUID:()=>`request-${++sequence}`},sessionStorage:{setItem:(k,v)=>saved.set(k,v),getItem:k=>saved.get(k),removeItem:k=>saved.delete(k)}});
 vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/context-ui.js'),'utf8'),ctx);
 vm.runInContext("businessConversation={conversationId:'owned-conversation',version:3}",ctx);
 return {ctx,saved,run:code=>vm.runInContext(code,ctx)};
}
test('new text cannot replace an unresolved original request or its persisted identity',()=>{
 const t=tab();const first=t.run("prepareContextRequest('分析账单')");const stored=t.saved.get('safebank.pendingChat');
 assert.throws(()=>t.run("prepareContextRequest('转账预览')"),e=>e.code==='ORIGINAL_REQUEST_PENDING');
 assert.equal(t.saved.get('safebank.pendingChat'),stored);
 assert.equal(t.run("prepareContextRequest('分析账单')"),first);
});
test('a completed answer releases the pending identity for the next question',()=>{
 const t=tab();const first=t.run("prepareContextRequest('分析账单')");
 t.run('acceptContextAnswer({})');
 const second=t.run("prepareContextRequest('核对大额支出')");
 assert.notEqual(first.requestId,second.requestId);assert.equal(second.text,'核对大额支出');
});
const elements=new Map();
function element(id){if(!elements.has(id))elements.set(id,{value:'',hidden:false,disabled:false,children:[],replaceChildren(...children){this.children=children;},append(...children){this.children.push(...children);},focus(){},querySelector(){return element('submit');}});return elements.get(id);}
function ui(t,request){Object.assign(t.ctx,{$:element,api:request,chatBusy:false,lastFailedQuestion:'',node:(tag,text)=>({text,type:tag}),message:()=>{}});return element;}

test('exhausted preflight preserves draft, releases unsent identity and hides reconciliation',async()=>{
 const t=tab();ui(t,()=>{throw Error('must not send');});
 t.run("prepareContextRequest('分析账单');lastFailedQuestion='分析账单'");
 await t.run("handleContextFailure({code:'MODEL_REQUEST_BUDGET_EXCEEDED',message:'额度已用完'})");
 assert.equal(t.run('pendingChatRequest'),null);assert.equal(element('prompt').value,'分析账单');assert.equal(element('chat-retry').hidden,true);
 assert.equal(t.saved.has('safebank.pendingChat'),false);
});

test('midrun review preserves original identity for reconciliation',async()=>{
 const t=tab();ui(t,()=>{});const first=t.run("prepareContextRequest('分析账单')");
 await t.run("handleContextFailure({code:'AGENT_REVIEW_REQUIRED',message:'额度不足，原轮次已暂停'})");
 assert.equal(t.run('pendingChatRequest'),first);
});

test('new conversation retains paused request and restoring never sends chat',async()=>{
 const t=tab(),calls=[];
 ui(t,async(path,body)=>{calls.push([path,body]);return path==='/conversations'?{conversationId:'new-cid',version:1}:{conversationId:'owned-conversation',version:3,displayHistory:[]};});
 t.run("prepareContextRequest('原问题')");const original=JSON.parse(t.saved.get('safebank.pendingChat'));
 element('prompt').value='尚未发送的新问题';
 await t.run('startNewBusinessConversation()');
 assert.equal(t.run('pendingChatRequest'),null);assert.equal(t.run('businessConversation.conversationId'),'new-cid');
 const archived=JSON.parse(t.saved.get('safebank.savedConversations'));
 assert.deepEqual(archived[0].pending,original);assert.equal(archived[0].draft,'尚未发送的新问题');
 await t.run("selectSavedConversation('owned-conversation')");
 assert.deepEqual(JSON.parse(t.saved.get('safebank.pendingChat')),original);
 assert.equal(element('prompt').value,'尚未发送的新问题');assert.equal(element('chat-retry').hidden,false);
 assert.deepEqual(calls.map(c=>c[0]),['/conversations','/conversations/owned-conversation']);
});

test('failed conversation creation preserves original request and tab state',async()=>{
 const t=tab();ui(t,async()=>{throw Error('offline');});const first=t.run("prepareContextRequest('原问题')");
 await assert.rejects(t.run('startNewBusinessConversation()'),/offline/);
 assert.equal(t.run('pendingChatRequest'),first);assert.equal(t.run('businessConversation.conversationId'),'owned-conversation');assert.equal(t.run('conversationSwitchBusy'),false);
});

test('conversation switching serializes requests and does not replace an in-flight turn',async()=>{
 const t=tab();let resolve;let calls=0;ui(t,()=>{calls++;return new Promise(r=>resolve=r);});
 const first=t.run('startNewBusinessConversation()');assert.equal(t.run('conversationSwitchBusy'),true);
 await t.run('startNewBusinessConversation()');assert.equal(calls,1);
 resolve({conversationId:'new-cid',version:1});await first;
 t.ctx.chatBusy=true;await t.run('startNewBusinessConversation()');assert.equal(calls,1);
});
