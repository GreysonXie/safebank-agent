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
