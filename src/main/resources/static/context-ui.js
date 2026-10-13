// Conversation state belongs to this tab. A retry reuses the exact original request.
let businessConversation=null,pendingChatRequest=null;
async function createMandateQueryIntent(){
 if(!['LANGGRAPH_MOCK','LANGGRAPH_MODEL','FAKE_AGENT'].includes(currentAgentMode))return api('/query-intents',{operation:'QUERY_MANDATES'});
 if(chatBusy||pendingChatRequest)throw Error('请等待当前对话请求完成，或恢复原请求后再查询代扣。');
 if(!businessConversation?.conversationId)renderBusinessContext(await api('/conversations',{}),null);
 return api('/query-intents',{operation:'QUERY_MANDATES',conversationId:businessConversation.conversationId,expectedContextVersion:businessConversation.version});
}
function contextStorageRead(key){try{return JSON.parse(sessionStorage.getItem(key)||'null');}catch{return null;}}
function contextStorageWrite(key,value){try{if(value===null)sessionStorage.removeItem(key);else sessionStorage.setItem(key,JSON.stringify(value));}catch{/* This tab still works when browser storage is unavailable. */}}
function prepareContextRequest(text){
 if(pendingChatRequest?.text===text)return pendingChatRequest;
 pendingChatRequest={text,modelRequestLimit:5,requestId:crypto.randomUUID()};
 if(businessConversation){pendingChatRequest.conversationId=businessConversation.conversationId;pendingChatRequest.expectedContextVersion=businessConversation.version;}
 contextStorageWrite('safebank.pendingChat',pendingChatRequest);return pendingChatRequest;
}
function renderBusinessContext(view,clarification){
 businessConversation=view;contextStorageWrite('safebank.context',view?{conversationId:view.conversationId,version:view.version}:null);
 const box=$('business-context');if(!box)return;
 const labels={ACTIVE:'上下文已保存',NEEDS_CLARIFICATION:'等待补充参数',AWAITING_CONFIRMATION:'待确认操作',PAUSED:'已暂停',EXPIRED:'上下文已过期'};
 const summary=[$('context-state')];summary[0].textContent=view?labels[view.state]||'上下文已保存':'从新问题开始';
 const items=[];if(view?.slots?.primary_month)items.push('账单 '+view.slots.primary_month);if(view?.slots?.comparison_month)items.push('比较 '+view.slots.comparison_month);
 if(view?.slots?.focused_transaction_id)items.push('已选中核对交易');if(view?.bankReferences?.length)items.push('原确认项与回执可继续核对');
 $('context-summary').textContent=items.length?items.join(' · '):'可承接月份、对象和待补参数；记忆不代表操作授权。';
 const options=$('context-choices');options.replaceChildren();
 if(clarification){options.append(node('p',clarification.question,'hint'));for(const r of clarification.recipientCandidates||[]){const button=node('button',r.name+' · '+r.note+' · '+r.label);button.type='button';button.onclick=()=>{$('prompt').value='选择收款人 '+r.name+'，备注 '+r.note;$('prompt').focus();};options.append(button);}}
 $('context-reset').disabled=!view||chatBusy;
}
function acceptContextAnswer(response){
 if(response.context)renderBusinessContext(response.context,response.clarification);
 pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);
}
async function restoreBusinessContext(showHistory=false){
 if(!businessConversation?.conversationId){renderBusinessContext(null,null);return;}
 try{const view=await api('/conversations/'+encodeURIComponent(businessConversation.conversationId));renderBusinessContext(view,null);
  if(showHistory){for(const turn of view.displayHistory||[]){if(turn.user)message(turn.user,'user');if(turn.assistant)message(turn.assistant);}}
 }catch(error){if(['NOT_FOUND','CONTEXT_EXPIRED'].includes(error.code)){businessConversation=null;pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);renderBusinessContext(null,null);$('chat-status').textContent='原对话不可用或已过期。新问题会建立上下文，已有银行动作请从历史回执核对。';}else throw error;}
}
async function initializeBusinessContext(user){
 const previous=contextStorageRead('safebank.contextUser');
 if(previous!==user){contextStorageWrite('safebank.context',null);contextStorageWrite('safebank.pendingChat',null);}
 contextStorageWrite('safebank.contextUser',user);businessConversation=contextStorageRead('safebank.context');pendingChatRequest=contextStorageRead('safebank.pendingChat');
 $('context-reset').onclick=()=>perform($('context-reset'),async()=>{if(!businessConversation)return;const view=await api('/conversations/'+encodeURIComponent(businessConversation.conversationId)+'/context/reset',{expectedContextVersion:businessConversation.version});renderBusinessContext(view,null);pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);$('messages').replaceChildren();message('对话草案已清空。原银行确认项仍需单独取消，已执行动作和回执保留。');$('chat-retry').hidden=true;});
 await restoreBusinessContext(true);
 if(pendingChatRequest){lastFailedQuestion=pendingChatRequest.text;$('chat-retry').hidden=false;$('chat-status').textContent='上次请求尚未收到完整回答。可恢复原请求，不会自动重发。';}
}
async function handleContextFailure(error){
 if(['CONFLICT','CONTEXT_INTERRUPTED'].includes(error.code)){pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);await restoreBusinessContext(false);$('chat-retry').hidden=true;$('chat-status').textContent=error.message+'。请核对最新状态后再提出新问题。';}
 else if(['NOT_FOUND','CONTEXT_EXPIRED'].includes(error.code)){businessConversation=null;pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);renderBusinessContext(null,null);$('chat-retry').hidden=true;$('chat-status').textContent='上下文已不可用，请重新提出问题；原动作仍从历史回执核对。';}
}
