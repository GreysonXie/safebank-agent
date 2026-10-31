const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../src/main/resources/static/context-ui.js'),'utf8');
function fixture(){
 const calls=[],elements=new Map();
 const context=vm.createContext({sessionStorage:{setItem(){},removeItem(){}},chatBusy:false,currentAgentMode:"LANGGRAPH_MOCK",api:async(url,body)=>{calls.push({url,body});return url==='/conversations'?{conversationId:'new',version:1,state:'ACTIVE'}:{id:'action'};},$:id=>{if(!elements.has(id))elements.set(id,{textContent:'',replaceChildren(){}});return elements.get(id);}});
 vm.runInContext(source,context);return {context,calls};
}
test('confirmed query shares existing tab conversation without sending chat',async()=>{
 const {context,calls}=fixture();vm.runInContext("businessConversation={conversationId:'existing',version:4}",context);
 await vm.runInContext('createMandateQueryIntent()',context);
 assert.deepEqual(JSON.parse(JSON.stringify(calls)),[{url:'/query-intents',body:{operation:'QUERY_MANDATES',conversationId:'existing',expectedContextVersion:4}}]);
});
test('query creates an empty conversation then binds its version without model calls',async()=>{
 const {context,calls}=fixture();await vm.runInContext('createMandateQueryIntent()',context);
 assert.deepEqual(JSON.parse(JSON.stringify(calls)),[{url:'/conversations',body:{}},{url:'/query-intents',body:{operation:'QUERY_MANDATES',conversationId:'new',expectedContextVersion:1}}]);
 assert.equal(vm.runInContext('businessConversation.conversationId',context),'new');
});
test('failed conversation creation never creates unbound query',async()=>{
 const {context,calls}=fixture();context.api=async url=>{calls.push(url);throw Error('unavailable');};
 await assert.rejects(vm.runInContext('createMandateQueryIntent()',context),/unavailable/);assert.deepEqual(calls,['/conversations']);
});


test('legacy offline query keeps original operation-only request',async()=>{
 const {context,calls}=fixture();context.currentAgentMode='OFFLINE_DEMO';await vm.runInContext('createMandateQueryIntent()',context);
 assert.deepEqual(JSON.parse(JSON.stringify(calls)),[{url:'/query-intents',body:{operation:'QUERY_MANDATES'}}]);
 assert.equal(vm.runInContext('businessConversation',context),null);
});
const appSource=fs.readFileSync(path.join(__dirname,'../../src/main/resources/static/app.js'),'utf8');
const pollSource=appSource.slice(appSource.indexOf('async function pollOriginalChat('),appSource.indexOf('async function sendQuestion('));
test('first chat binds a new tab conversation before server session fallback',async()=>{
 const {context,calls}=fixture();context.crypto={randomUUID:()=> 'request-new-tab'};vm.runInContext(pollSource,context);
 await vm.runInContext("pollOriginalChat('季度安全服务')",context);
 assert.equal(calls[0].url,'/conversations');assert.equal(calls[1].url,'/chat');
 assert.equal(calls[1].body.conversationId,'new');assert.equal(calls[1].body.expectedContextVersion,1);
});
