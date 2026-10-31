// Conversation state belongs to this tab. A retry reuses the exact original request.
let businessConversation=null,pendingChatRequest=null;
let savedConversations=[],conversationSwitchBusy=false;
async function createMandateQueryIntent(){
 if(!['LANGGRAPH_MOCK','LANGGRAPH_MODEL','FAKE_AGENT'].includes(currentAgentMode))return api('/query-intents',{operation:'QUERY_MANDATES'});
 if(chatBusy||conversationSwitchBusy||pendingChatRequest)throw Error('请等待当前对话请求完成，或恢复原请求后再查询代扣。');
 if(!businessConversation?.conversationId)renderBusinessContext(await api('/conversations',{}),null);
 return api('/query-intents',{operation:'QUERY_MANDATES',conversationId:businessConversation.conversationId,expectedContextVersion:businessConversation.version});
}
function contextStorageRead(key){try{return JSON.parse(sessionStorage.getItem(key)||'null');}catch{return null;}}
function contextStorageWrite(key,value){try{if(value===null)sessionStorage.removeItem(key);else sessionStorage.setItem(key,JSON.stringify(value));}catch{/* This tab still works when browser storage is unavailable. */}}
function prepareContextRequest(text){
 if(pendingChatRequest?.text===text)return pendingChatRequest;
 if(pendingChatRequest){
  const error=Error('上次请求尚未完成，请先核对原请求，再发送新问题。');
  error.code='ORIGINAL_REQUEST_PENDING';throw error;
 }
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
 $('context-reset').disabled=!view||chatBusy||!!pendingChatRequest;
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
 if(previous!==user){contextStorageWrite('safebank.context',null);contextStorageWrite('safebank.pendingChat',null);contextStorageWrite('safebank.savedConversations',null);}
 savedConversations=contextStorageRead('safebank.savedConversations')||[];
 $('context-new').onclick=()=>perform($('context-new'),startNewBusinessConversation);renderSavedConversations();
 contextStorageWrite('safebank.contextUser',user);businessConversation=contextStorageRead('safebank.context');pendingChatRequest=contextStorageRead('safebank.pendingChat');
 $('context-reset').onclick=()=>perform($('context-reset'),async()=>{if(!businessConversation)return;const view=await api('/conversations/'+encodeURIComponent(businessConversation.conversationId)+'/context/reset',{expectedContextVersion:businessConversation.version});renderBusinessContext(view,null);pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);$('messages').replaceChildren();message('对话草案已清空。原银行确认项仍需单独取消，已执行动作和回执保留。');$('chat-retry').hidden=true;});
 await restoreBusinessContext(true);
 if(pendingChatRequest){lastFailedQuestion=pendingChatRequest.text;$('chat-retry').hidden=false;$('chat-status').textContent='上次请求尚未收到完整回答。可恢复原请求，不会自动重发。';}
}
async function handleContextFailure(error){
 if(error.code==='MODEL_REQUEST_BUDGET_EXCEEDED'){releaseFailed(error,'。问题已保留在输入框，可直接修改后再问。');}
 else if(['CONFLICT','CONTEXT_INTERRUPTED'].includes(error.code)){pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);await restoreBusinessContext(false);$('chat-retry').hidden=true;$('chat-status').textContent=error.message+'。请核对最新状态后再提出新问题。';}
 else if(['NOT_FOUND','CONTEXT_EXPIRED'].includes(error.code)){businessConversation=null;pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);renderBusinessContext(null,null);$('chat-retry').hidden=true;$('chat-status').textContent='上下文已不可用，请重新提出问题；原动作仍从历史回执核对。';}
 else if(['AGENT_REVIEW_REQUIRED','AGENT_RECONCILING'].includes(error.code)){offerTurnClose(error);}
 else{releaseFailed(error,'。本轮没有执行任何银行操作，可以直接换一种问法再问。');}
}
/** 明确失败（无银行写操作）不是"待核对"：释放本地的待处理请求，避免下一句话被当成替换原请求。 */
function releaseFailed(error,note){
 pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);
 $('chat-turn-close').hidden=true;$('chat-retry').hidden=true;
 if(!$('prompt').value.trim())$('prompt').value=lastFailedQuestion;
 $('chat-status').textContent=error.message+(note||'');
}
/** 原请求卡在待核对时，给出“结束本次处理”的安全出口：只结束该轮，不重发、不作废已执行动作。 */
function offerTurnClose(error){
 const bar=$('chat-turn-close');const conversationId=businessConversation?.conversationId,requestId=pendingChatRequest?.requestId;
 if(!conversationId||!requestId){releaseFailed(error,'。可直接重新提问；如仍被拦住，刷新页面后再试。');return;}
 bar.hidden=false;$('chat-turn-close-note').textContent=error.message+'；如确认不再等待，可结束本次处理，原动作与回执保留。';
 $('chat-turn-close-button').onclick=()=>perform($('chat-turn-close-button'),async()=>{
  const outcome=await api('/conversations/'+encodeURIComponent(conversationId)+'/turns/'+encodeURIComponent(requestId)+'/close',{});
  pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);bar.hidden=true;$('chat-retry').hidden=true;
  $('chat-status').textContent=outcome.state==='INTERRUPTED'?'本次处理已结束，未执行新的资金操作；已生成的操作仍需在原记录单独核对，现在可以直接问新问题。':'本次处理未能结束：'+(outcome.reason||outcome.state||'状态未确认')+'。请核对原记录后再操作。';
  if(outcome.agentUnblocked===false)$('chat-status').textContent+=' 助手侧占用仍未解除（'+outcome.agentReason+'），可稍后重试。';
 });
}

function archiveActiveConversation(){
 if(!businessConversation?.conversationId)return;
 const saved={context:businessConversation,pending:pendingChatRequest,draft:$('prompt').value,label:pendingChatRequest?.text||'对话 '+new Date().toLocaleString()};
 savedConversations=savedConversations.filter(item=>item.context.conversationId!==businessConversation.conversationId);
 savedConversations.unshift(saved);contextStorageWrite('safebank.savedConversations',savedConversations);
}
function renderSavedConversations(){
 const box=$('context-saved');box.replaceChildren();
 if(!savedConversations.length){box.append(node('p','暂无保留的对话','hint'));return;}
 for(const saved of savedConversations){
  const button=node('button',saved.label.slice(0,60)+(saved.pending?' · 原请求待核对':''));button.type='button';button.disabled=chatBusy||conversationSwitchBusy;
  button.onclick=()=>perform(button,()=>selectSavedConversation(saved.context.conversationId));box.append(button);
 }
}
function activateConversation(context,pending,draft){
 pendingChatRequest=pending;contextStorageWrite('safebank.pendingChat',pending);
 renderBusinessContext(context,null);$('messages').replaceChildren();$('prompt').value=draft||'';
 lastFailedQuestion=pending?.text||'';$('chat-retry').hidden=!pending;
 $('chat-status').textContent=pending?'原请求已恢复，点击“核对原请求”查看状态。':'';
 renderSavedConversations();
}
function setConversationSwitchBusy(value){
 conversationSwitchBusy=value;$('context-new').disabled=value;
 $('chat-form').querySelector('button[type="submit"]').disabled=value;
 $('context-reset').disabled=value||!businessConversation||!!pendingChatRequest;renderSavedConversations();
}
async function startNewBusinessConversation(){
 if(chatBusy||conversationSwitchBusy)return;
 setConversationSwitchBusy(true);
 try{const view=await api('/conversations',{});
 archiveActiveConversation();activateConversation(view,null,'');
 message('新对话已建立。旧对话保留在“已保留的对话”，可返回核对；银行确认项和回执仍可查看。');
 $('prompt').focus();
 }finally{setConversationSwitchBusy(false);}
}
async function selectSavedConversation(id){
 if(chatBusy||conversationSwitchBusy)return;
 const saved=savedConversations.find(item=>item.context.conversationId===id);if(!saved)return;
 setConversationSwitchBusy(true);
 try{const view=await api('/conversations/'+encodeURIComponent(id));
 archiveActiveConversation();savedConversations=savedConversations.filter(item=>item.context.conversationId!==id);
 contextStorageWrite('safebank.savedConversations',savedConversations);
 activateConversation(view,saved.pending,saved.draft);
 for(const turn of view.displayHistory||[]){if(turn.user)message(turn.user,'user');if(turn.assistant)message(turn.assistant);}
 }finally{setConversationSwitchBusy(false);}
}
