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
 if(previous!==user){contextStorageWrite('safebank.context',null);contextStorageWrite('safebank.pendingChat',null);}
 contextStorageWrite('safebank.contextUser',user);businessConversation=contextStorageRead('safebank.context');pendingChatRequest=contextStorageRead('safebank.pendingChat');
 $('context-reset').onclick=()=>perform($('context-reset'),async()=>{if(!businessConversation)return;const view=await api('/conversations/'+encodeURIComponent(businessConversation.conversationId)+'/context/reset',{expectedContextVersion:businessConversation.version});renderBusinessContext(view,null);pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);$('messages').replaceChildren();message('对话草案已清空。原银行确认项仍需单独取消，已执行动作和回执保留。');$('chat-retry').hidden=true;});
 await restoreBusinessContext(true);
 if(pendingChatRequest){lastFailedQuestion=pendingChatRequest.text;$('chat-retry').hidden=false;$('chat-status').textContent='上次请求尚未收到完整回答。可恢复原请求，不会自动重发。';await restorePendingTurn();}
}
/** 刷新后按原请求定位：有答案就展示，已暂停给恢复入口，已结束则释放 pending 并保留草稿。 */
async function restorePendingTurn(){
 const info=await resolvePendingTurn();if(!info)return;
 if(info.hasOutput&&info.answer&&typeof acceptRecoveredAnswer==='function'){await acceptRecoveredAnswer(info.answer);releasePendingTurn();$('chat-status').textContent='已取回本轮的完整回答；原动作请从任务记录核对。';return;}
 if(info.closed){releasePendingTurn();$('chat-status').textContent='上一轮已结束，未取得答案。可以继续提出新问题。';return;}
 if(info.requiresReview){$('chat-status').textContent='上一问已暂停，未取得可核验答案。可再核对一次，或结束本轮后继续。';renderRecoveryPanel();}
}
function releasePendingTurn(){pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);$('chat-retry').hidden=true;const panel=$('chat-recovery');if(panel)panel.remove();}
async function resolvePendingTurn(){
 if(!pendingChatRequest)return null;const cid=(pendingChatRequest.conversationId)||(businessConversation&&businessConversation.conversationId);if(!cid)return null;
 try{return await api('/agent/requests/'+encodeURIComponent(pendingChatRequest.requestId)+'?conversationId='+encodeURIComponent(cid));}catch(e){return null;}
}
function renderRecoveryPanel(){
 let panel=$('chat-recovery');
 if(!panel){panel=document.createElement('div');panel.id='chat-recovery';panel.className='hint';$('chat-status').after(panel);}
 panel.replaceChildren();
 const text=document.createElement('p');text.textContent='本轮未取得可核验答案；原请求与原单都保留，银行动作不会因为结束聊天而被取消。';panel.append(text);
 const again=document.createElement('button');again.type='button';again.textContent='再核对一次';
 again.onclick=()=>perform(again,async()=>{const info=await resolvePendingTurn();if(info&&info.hasOutput&&info.answer){await acceptRecoveredAnswer(info.answer);releasePendingTurn();$('chat-status').textContent='已取回本轮的完整回答。';return;}if(info&&info.closed){releasePendingTurn();$('chat-status').textContent='上一轮已结束，未取得答案。可以继续提出新问题。';return;}message('仍未取得可核验结果，可结束本轮后继续提问。');});
 const close=document.createElement('button');close.type='button';close.textContent='结束本轮并继续';
 close.onclick=()=>perform(close,async()=>{const cid=(pendingChatRequest.conversationId)||(businessConversation&&businessConversation.conversationId);if(!cid)throw Error('缺少对话编号，请刷新页面后重试。');
  const outcome=await api('/agent/requests/'+encodeURIComponent(pendingChatRequest.requestId)+'/close',{conversationId:cid});
  if(outcome&&outcome.agentUnblocked===false){
   $('chat-status').textContent='聊天已结束，但该会话尚未解锁：'+(outcome.blockingTurnId?('另一轮（'+String(outcome.blockingTurnId).slice(0,8)+'…）仍待核对，请先核对或结束那一轮。'):('服务端清理未完成（'+outcome.agentReason+'），可稍后重试。'));
   const retry=document.createElement('button');retry.type='button';retry.textContent='重试解锁';
   const panel=$('chat-recovery');panel.replaceChildren();
   const note=document.createElement('p');note.textContent='本轮聊天已结束，原单与银行动作未变。';panel.append(note,retry);
   retry.onclick=()=>perform(retry,async()=>{const again=await api('/agent/requests/'+encodeURIComponent(pendingChatRequest.requestId)+'/close',{conversationId:cid});if(again&&again.agentUnblocked!==false){releasePendingTurn();$('chat-status').textContent='会话已解锁，可以继续提出新问题。';}else{$('chat-status').textContent='仍未解锁：'+(again&&again.agentReason||'请稍后重试')+'；可先核对另一个待处理轮次。';}});
   return;
  }
  releasePendingTurn();$('chat-status').textContent='本轮已结束，未取得答案。可以继续提出新问题；原动作请从任务记录核对。';await restoreBusinessContext(false);});
 panel.append(again,close);
}
async function handleContextFailure(error){
 if(['CONFLICT','CONTEXT_INTERRUPTED'].includes(error.code)){pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);await restoreBusinessContext(false);$('chat-retry').hidden=true;$('chat-status').textContent=error.message+'。请核对最新状态后再提出新问题。';}
 else if(['NOT_FOUND','CONTEXT_EXPIRED'].includes(error.code)){businessConversation=null;pendingChatRequest=null;contextStorageWrite('safebank.pendingChat',null);renderBusinessContext(null,null);$('chat-retry').hidden=true;$('chat-status').textContent='上下文已不可用，请重新提出问题；原动作仍从历史回执核对。';}
 else if(error.code==='AGENT_TURN_CLOSED'){releasePendingTurn();$('chat-status').textContent='本轮已结束，未取得答案。可以继续提出新问题。';}
 else if(error.code==='AGENT_REVIEW_REQUIRED'){$('chat-status').textContent='上一问已暂停，未取得可核验答案。可再核对一次，或结束本轮后继续。';renderRecoveryPanel();}
 else{releasePendingTurn();$('chat-status').textContent='本次没有拿到回答（'+(error.code||'未知原因')+'）。可以直接继续提问；原请求可在任务记录里核对。';}
}
