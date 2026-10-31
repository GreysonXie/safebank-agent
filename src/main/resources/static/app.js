let csrf='',sessionRequest=null,faultsEnabled=false,workflows=[],currentAgentMode='',currentModelName='';
let chatBusy=false,lastFailedQuestion='',appReady=false;
const actionCards=new Map(),verificationProofs=new Map(),actionRows=new Map();
let openActionId=null;
const accountLabels=new Map();
const recipientLabel=id=>window.workspaceRecipientLabel?.(id)||id;
let confirmedMandates=null;
const $=id=>document.getElementById(id);
const money=n=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(n)/100);
const billingLabel=cycle=>({MONTHLY:'按月',QUARTERLY:'按季度',YEARLY:'按年'}[cycle]||'周期未确认');
const mandateStatusLabel=status=>({ACTIVE:'银行侧授权有效',REVOKED:'银行侧授权已撤销'}[status]||'银行侧状态未确认');
const statusLabel={AWAITING_AUTH:'待确认 · 尚未执行',AUTHORIZED:'已授权 · 尚未提交',VERIFIED_SUCCESS:'已核验完成',VERIFIED_FAILED:'已核验失败',CANCELLED:'已取消',CANCEL_REQUESTED:'已停止后续步骤 · 原动作待核实',PENDING:'银行处理中',UNKNOWN:'状态待核实',PARTIAL_SUCCESS:'部分成功 · 查看各步骤'};
function node(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function notify(message){$('notification').textContent=message;$('notification').style.display='block';clearTimeout(notify.timer);notify.timer=setTimeout(()=>$('notification').style.display='none',6500);}
async function api(path,body){const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),110000);try{const response=await fetch('/api'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});const data=await response.json();if(!response.ok){const error=Error(data.message||data.code||'请求未完成');Object.assign(error,data);throw error;}return data;}finally{clearTimeout(timer);}}
async function ensureSession(){
 if(!sessionRequest)sessionRequest=api('/session').then(session=>{csrf=session.csrf;faultsEnabled=session.faultsEnabled;currentAgentMode=session.mode;if(session.businessDate)window.bankBusinessDate=session.businessDate;return session;}).finally(()=>{sessionRequest=null;});
 return sessionRequest;
}
function amount(value){if(!/^\d+(\.\d{1,2})?$/.test(value.trim()))throw Error('金额最多两位小数，且必须为正数');const [whole,fraction='']=value.trim().split('.');const minor=Number(whole)*100+Number(fraction.padEnd(2,'0'));if(!Number.isSafeInteger(minor)||minor<=0)throw Error('金额无效');return minor;}
async function perform(button,fn){if(!appReady){notify('正在恢复业务会话，请稍候。');return;}button.disabled=true;try{await fn();}catch(e){notify(e.message);}finally{if(button.isConnected)button.disabled=false;}}
function setMode(mode,model){currentAgentMode=mode;if(model)currentModelName=model;model=currentModelName;if(mode==='LANGGRAPH_MOCK'||mode==='LANGGRAPH_MODEL'){$('mode').textContent=mode==='LANGGRAPH_MOCK'?'LangGraph · 离线模拟模型':'LangGraph · 模型已配置';$('model-info').textContent=mode==='LANGGRAPH_MOCK'?'正式 LangGraph 对话图已接入，当前使用离线模拟模型，不消耗 API 额度。':'模型：'+model+' · Java 核验业务事实与权限 · 项目调用不限次数';return;}if(mode==='FAKE_AGENT'){$('mode').textContent='网关协议模拟';$('model-info').textContent='当前连接模拟 Python 服务，验证只读工具、权限和恢复协议；未调用大模型。';return;}$('mode').textContent=mode==='OFFLINE_DEMO'?'离线工具演示':mode==='CONNECTION_VERIFIED'?'模型连接已验证':'模型已配置 · 未验证';$('model-info').textContent=mode==='OFFLINE_DEMO'?'未设置模型密钥。对话采用有限规则匹配，联合任务可通过表单创建，银行操作执行于本地模拟环境。':'模型：'+model+' · '+(mode==='CONNECTION_VERIFIED'?'本次进程已获得有效响应':'尚未获得有效连接响应')+' · 写操作须逐项授权';}
async function refreshModelBudget(){
 const box=$('model-budget');
 if(!['LANGGRAPH_MOCK','LANGGRAPH_MODEL'].includes(currentAgentMode)){box.textContent='';return;}
 try{const budget=await api('/model-budget');box.textContent=budget.applies?'模型请求已记录 '+budget.used+' 次 · 项目不限次数。供应商余额与限流以供应商为准。':'离线模拟模型，不消耗 API 额度。';}
 catch{box.textContent='模型用量暂时无法读取，请稍后刷新。';}
}
async function refresh(){
 const [accounts,schedules,audit,taskList]=await Promise.all([api('/accounts'),api('/schedules'),api('/audit'),api('/workflows')]);
 workflows=taskList;
 for(const account of accounts)accountLabels.set(account.id,account.label);
 $('accounts').replaceChildren(...accounts.map(a=>{const n=node('article',undefined,'account');n.append(node('p',a.label+' · 合成账户'),node('strong',money(a.balance_minor)));return n;}));
 renderMandates(confirmedMandates);
 if(window.bankUI)await window.bankUI.refreshSavings(schedules);
 $('audit').textContent=JSON.stringify(audit,null,2);
 if(window.refreshWorkspace)await window.refreshWorkspace(accounts);
 $('schedules').replaceChildren(...schedules.map(s=>node('p','已登记：'+recipientLabel(s.recipient_id)+' · 每月 '+s.day_of_month+' 日 · '+money(s.amount_minor)+' · 首次 '+s.first_date,'hint')));
 renderWorkflows();
}
function renderMandates(mandates){if(window.bankUI)window.bankUI.renderMandates(mandates);}
function applyReceipt(receipt){
 if(receipt.kind==='QUERY_MANDATES'&&Array.isArray(receipt.data)){confirmedMandates=receipt.data;renderMandates(confirmedMandates);notify('代扣查询已确认，现在可以勾选有效月付项目。');$('chat-status').textContent='查询已确认，可选择月度代扣，或继续提问。';}
 if(receipt.kind==='ACTIVATE_MANDATE'&&receipt.status==='VERIFIED_SUCCESS'&&confirmedMandates){const record={id:receipt.mandate_id,merchant:receipt.merchant,amount_minor:receipt.charge_amount_minor,billing_interval:receipt.billing_interval,renewal_date:receipt.renewal_date,status:'ACTIVE'};confirmedMandates=[...confirmedMandates.filter(m=>m.id!==record.id),record];renderMandates(confirmedMandates);}
 if(receipt.kind==='REVOKE'&&receipt.status==='VERIFIED_SUCCESS'&&confirmedMandates){confirmedMandates=confirmedMandates.map(m=>m.id===receipt.mandate_id?{...m,status:'REVOKED'}:m);renderMandates(confirmedMandates);}
}
function cardError(id,error){const card=actionCards.get(id);if(!card)return;let target=card.querySelector('.risk-error');if(!target){target=node('p',undefined,'risk-error');target.id='error-'+id;target.setAttribute('role','alert');target.tabIndex=-1;card.prepend(target);}target.textContent=error.message+(error.retryAt?' · 可重试时间 '+new Date(error.retryAt).toLocaleString():'');target.focus();}
function renderWorkflows(){
 $('workflows').replaceChildren(...workflows.map(w=>{
  const box=node('article',undefined,'workflow-item');box.dataset.workflowId=w.id;
  box.append(node('h3','联合任务 · 版本 '+w.version+' · '+(statusLabel[w.state]||w.state)),node('p','账单 '+w.request.month+' → '+recipientLabel(w.request.recipientId)+' · 预计月度节省 '+money(w.estimatedSavingMinor)+'（仅计本任务已核验撤销）','hint'));
  const list=node('ol',undefined,'workflow-steps');for(const step of w.steps){const item=node('li',(step.kind==='REVOKE'?'撤销 '+step.key.substring(7):'登记月度储蓄')+' · '+(statusLabel[step.status]||step.status)+' · v'+step.inputVersion+(step.errorCode?' · '+step.errorCode:''));const inspect=node('button','查看动作');inspect.type='button';inspect.onclick=()=>perform(inspect,async()=>{showAction(await api('/actions/'+step.actionId));window.bankUI?.revealAction(step.actionId);});item.append(inspect);list.append(item);}
  const history=node('details');history.append(node('summary','操作记录与回执（'+w.steps.length+'）'),list);box.append(history);
  const planDetails=node('details');planDetails.append(node('summary','查看完整计划与依赖'));const planBody=node('div');planDetails.append(planBody);
  planDetails.ontoggle=async()=>{if(!planDetails.open)return;planBody.replaceChildren(node('p','正在读取原任务步骤…','hint'));
   try{const plan=await api('/workflows/'+w.id+'/plan');if(!planDetails.isConnected)return;
    if(plan.version!==w.version){planBody.replaceChildren(node('p','任务版本已变化，请刷新后查看。','hint'));return;}
    const names={mandate_lookup:'核对代扣查询',account_query:'核对账户',mandate_revoke:'撤销银行代扣',compute_savings:'核算已确认节省',schedule_create:'登记定时储蓄'};
    const states={VALIDATED:'前置已校验',WAITING_DEPENDENCY:'等待前置步骤',BLOCKED_UNKNOWN:'结果未知，等待查单',AWAITING_AUTH:'等待逐项确认',AUTHORIZED:'已授权，尚未核验完成',VERIFIED_SUCCESS:'已核验完成',VERIFIED_FAILED:'未完成',CANCELLED:'已取消',PENDING:'银行处理中'};
    const stepNames=Object.fromEntries(plan.nodes.map((step,index)=>[step.id,'步骤 '+(index+1)+'：'+(names[step.type]||'业务步骤')]));
    const steps=node('ol',undefined,'workflow-steps');for(const step of plan.nodes){const item=node('li',(names[step.type]||'业务步骤')+' · '+(states[step.displayState]||step.displayState));if(step.dependsOn.length)item.append(node('small','前置：'+step.dependsOn.map(id=>stepNames[id]||'待核对步骤').join('；'),'hint'));steps.append(item);}planBody.replaceChildren(steps,node('p','计划登记和前置校验不代表银行操作完成；定时委托登记也不代表未来到账。','hint'));
   }catch(error){if(planDetails.isConnected)planBody.replaceChildren(node('p','步骤读取失败：'+error.message,'hint'));}
  };box.append(planDetails);if(w.partial)box.append(node('p','已有步骤完成；取消或修改后续任务不会撤回这些银行操作。','hint'));
  if(w.state==='VERIFIED_SUCCESS'||w.state==='PARTIAL_SUCCESS')box.append(node('p','请逐项检查结果。定时计划登记不代表未来转账已发生；商户合同状态仍未知。','hint'));
  const controls=node('div',undefined,'workflow-controls');const resume=node('button','查单并继续');resume.onclick=()=>perform(resume,async()=>{await api('/workflows/'+w.id+'/resume',{version:w.version});await refresh();});controls.append(resume);
  if(w.pendingAction&&w.pendingAction.id){const inspect=node('button','查看当前确认项');inspect.onclick=()=>{showAction(w.pendingAction);window.bankUI?.revealAction(w.pendingAction.id);};controls.append(inspect);showAction(w.pendingAction);}
  if(!['VERIFIED_SUCCESS','PARTIAL_SUCCESS','CANCELLED','CANCEL_REQUESTED'].includes(w.state)){
   const cancel=node('button','取消后续任务');cancel.onclick=()=>perform(cancel,async()=>{await api('/workflows/'+w.id+'/cancel',{version:w.version});await refresh();await restoreActions();});controls.append(cancel);
   const details=node('details');details.append(node('summary','修改后续参数（原待确认项将失效）'));
   const form=node('form',undefined,'workflow-edit');const target=node('select');for(const [id,label] of (window.workspaceSavingsOptions?.()||[])){const option=node('option',label);option.value=id;target.append(option);}target.value=w.request.recipientId;
   const first=node('input'),end=node('input');first.type=end.type='date';first.value=w.request.firstDate;end.value=w.request.endDate;first.required=end.required=true;
   const day=node('select');for(let i=1;i<=31;i++){const option=node('option',i+' 日'+(i>28?' · 短月按月末':''));option.value=i;day.append(option);}day.value=w.request.dayOfMonth;
   for(const [caption,input] of [['目标',target],['每月转入日',day],['首次日期',first],['结束日期',end]]){const label=node('label',caption);label.append(input);form.append(label);}
   const save=node('button','保存新版本');form.append(save);form.onsubmit=e=>{e.preventDefault();perform(save,async()=>{await api('/workflows/'+w.id+'/revise',{version:w.version,request:{...w.request,recipientId:target.value,firstDate:first.value,endDate:end.value,dayOfMonth:Number(day.value)}});await refresh();await restoreActions();});};details.append(form);box.append(details);
  }
  box.append(controls);return box;
 }));
}
function mirrorTranscript(source){
 const mirror=$('chat-transcript');if(!mirror||!source)return;
 const empty=mirror.querySelector('.transcript-empty');if(empty)empty.remove();
 mirror.append(source.cloneNode(true));mirror.scrollTop=mirror.scrollHeight;
}
function resetTranscript(){
 $('messages').replaceChildren();
 const mirror=$('chat-transcript');
 if(mirror)mirror.replaceChildren(node('p','还没有对话内容。在右侧输入框描述你的需求，这里会同步显示完整对话。','hint transcript-empty'));
}
function message(text,role='assistant'){const n=node('div',undefined,'bubble '+role);n.append(node('small',role==='user'?'你的需求':'SafeBank'),node('div',text));$('messages').append(n);$('messages').scrollTop=$('messages').scrollHeight;mirrorTranscript(n);}
function showEvidenceTrace(trace){
 const names={get_statement:'查询账单',get_accounts:'查询余额',list_mandates:'查询银行代扣',detect_recurring_payments:'查询重复扣费候选',resolve_recipient:'核对收款人',calculate_business_metric:'计算业务数据',answer_customer:'根据问题组织回答',get_action_status:'核对银行动作状态',list_transfer_schedules:'查询定时计划',preview_transfer:'生成转账确认卡',preview_revoke:'生成代扣撤销确认卡',preview_schedule:'生成计划确认卡',start_savings_workflow:'创建逐项授权任务',get_workflow:'查询任务进度'};
 const details=node('details',undefined,'bubble assistant');details.append(node('summary','查看本轮查询依据'));const list=node('ul');
 for(const entry of trace){const tool=entry.split(' / ')[0];list.append(node('li',entry.includes('ANSWER_NOT_GROUNDED')?'核对并修正回答依据':names[tool]||'检查业务参数'));}
 details.append(list);$('messages').append(details);$('messages').scrollTop=$('messages').scrollHeight;mirrorTranscript(details);
}
function dataContent(data){
 const box=node('section',undefined,'result-block');
 if(data&&data.expense_minor!==undefined){
  box.append(node('h3',data.month+' 账单'));
  const totals=node('div',undefined,'statement-totals');for(const[label,value]of [['收入',data.income_minor],['支出',data.expense_minor]]){const card=node('div',undefined,'statement-total');card.append(node('span',label),node('strong',money(value)));totals.append(card);}box.append(totals,node('p','入账包含退款；内部转账不计入收支。净收支 '+money(data.income_minor-data.expense_minor),'hint'),node('h3','支出分类'));
  for(const[category,total]of Object.entries(data.categories).sort((a,b)=>b[1]-a[1])){const row=node('div',undefined,'category-row');row.append(node('span',category),node('strong',money(total)));const track=node('div',undefined,'category-track'),bar=node('div',undefined,'category-bar');bar.style.width=(data.expense_minor?Math.min(100,Number(total)/data.expense_minor*100):0)+'%';track.append(bar);row.append(track);box.append(row);}
  if(!data.transactions.length)box.append(node('p','这个月份没有查询到交易记录。','hint'));
  if(data.internal_transfer_minor)box.append(node('p','已排除本人账户内部转账 '+money(data.internal_transfer_minor),'hint'));
  const open=node('button','在独立交易明细中查看 '+data.month);open.type='button';open.onclick=()=>window.openStatementMonth?.(data.month);box.append(open);
 }else if(Array.isArray(data)&&data.length&&data[0].balance_minor!==undefined){box.append(node('h3','查询到的账户余额'));for(const a of data)box.append(node('p',a.label+'：'+money(a.balance_minor),'result-total'));}
 else if(Array.isArray(data)&&data.length&&data[0].merchant!==undefined){box.append(node('h3',data[0].occurrences!==undefined?'周期扣费候选':'银行侧代扣'));for(const m of data){const text=m.occurrences!==undefined?m.merchant+' · '+m.occurrences+'次 · '+money(m.min_amount_minor)+' 至 '+money(m.max_amount_minor):m.merchant+' · '+money(m.amount_minor)+' · '+billingLabel(m.billing_interval)+' · '+mandateStatusLabel(m.status);box.append(node('p',text,'transaction-row'));}}
 else if(data&&data.workflow){box.append(node('h3','联合任务已保存'),node('p','任务编号 '+data.workflow.id+' · '+(statusLabel[data.workflow.state]||data.workflow.state),'hint'));}
 else if(data&&data.code){box.append(node('p',data.message+'（'+data.code+'）','result-error'));}
 if(data&&Object.keys(data).length){const details=node('details');details.append(node('summary','原始银行数据'));details.append(node('pre',JSON.stringify(data,null,2)));box.append(details);}
 return box;
}
function showData(data,results){$('tool-result').replaceChildren();if(results&&results.length){for(const result of results)$('tool-result').append(dataContent(result.data));}else if(data&&Object.keys(data).length)$('tool-result').append(dataContent(data));}
function receiptMessage(r){if(r.status!=='VERIFIED_SUCCESS')return (statusLabel[r.status]||r.status)+'。请以查单结果为准，勿重新创建相同动作。';if(r.kind==='QUERY_MANDATES')return Array.isArray(r.data)?'本次代扣查询已完成；查询没有转账或记账。':'这是原查询回执，重新查询需再次确认范围。';if(r.kind==='ACTIVATE_MANDATE')return '银行侧代扣授权已开启。本次未扣款，商户服务与合同需另行确认。';if(r.kind==='MANAGE_SCHEDULE')return ({EDIT:'储蓄规则已更新，后续执行按新授权；原转账记录已保留。',PAUSE:'储蓄计划已暂停。',RESUME:'储蓄计划已恢复，按后续计划日执行，不补扣历史期次。',CANCEL:'储蓄计划已终止。已受理或完成的转账仍以原回执为准。'}[r.operation]||'储蓄计划管理已完成。');return r.kind==='SCHEDULE'?'定时计划已登记，尚未发生转账。':r.kind==='REVOKE'?'银行侧代扣已撤销，商户合同状态未知，余额没有因此增加。':'转账已完成，并核验双边账务分录。';}
async function resumeAssociated(actionId){for(const w of workflows)if(w.steps.some(step=>step.actionId===actionId)&&!['CANCELLED','VERIFIED_SUCCESS','PARTIAL_SUCCESS'].includes(w.state))await api('/workflows/'+w.id+'/resume',{version:w.version});}
/** 预览过期后不再可办：服务端授权/提交会以 AUTH_EXPIRED 拒绝，界面不能把它继续当成可确认项。 */
function actionExpired(action){return !!action.expires_at&&new Date(action.expires_at).getTime()<=Date.now()&&!['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(action.status);}
function actionTitle(a){return {TRANSFER:'转账操作',REVOKE:'撤销银行侧代扣',SCHEDULE:'创建定时转账',QUERY_MANDATES:'查询本人银行代扣',ACTIVATE_MANDATE:'开启银行侧代扣',MANAGE_SCHEDULE:({EDIT:'修改储蓄计划',PAUSE:'暂停储蓄计划',RESUME:'恢复储蓄计划',CANCEL:'终止储蓄计划'}[a.operation]||'管理储蓄计划')}[a.kind]||a.kind;}
function actionMeta(a){
 const parts=[];if(a.amount_minor!==undefined)parts.push(money(a.amount_minor));if(a.charge_amount_minor!==undefined)parts.push('单期 '+money(a.charge_amount_minor));
 if(a.recipient_label||a.recipient_id)parts.push(a.recipient_label||recipientLabel(a.recipient_id));if(a.merchant)parts.push(a.merchant);
 if(a.first_date)parts.push('首次 '+a.first_date);if(a.renewal_date)parts.push('约定扣费 '+a.renewal_date);
 return parts.join(' · ')||a.id;
}
/** 列表只放摘要行；完整核对卡在弹窗里打开，避免待办与业务内容混排。 */
function renderActionRow(a,expired,archived){
 const row=node('article',undefined,'action-row');row.dataset.actionId=a.id;row.dataset.status=a.status;row.dataset.title=actionTitle(a);
 if(expired)row.dataset.expired='true';
 const main=node('div',undefined,'action-row-main'),head=node('div',undefined,'action-row-head');
 head.append(node('strong',actionTitle(a)),node('span',expired?'预览已过期 · 未执行':statusLabel[a.status]||a.status,'status'));
 main.append(head,node('p',actionMeta(a),'action-row-meta'),node('small',a.id,'action-row-id'));
 const open=node('button',expired?'查看记录':archived?'查看回执':'处理','primary');open.type='button';
 open.setAttribute('aria-label',(expired?'查看已过期预览 ':'处理操作 ')+actionTitle(a));open.onclick=()=>openActionDialog(a.id);row.append(main,open);
 return row;
}
function openActionDialog(id){
 const card=actionCards.get(id);if(!card)return;
 const row=actionRows.get(id),dialog=$('action-dialog');if(!dialog)return;
 $('action-dialog-title').textContent=(row?.dataset.title?row.dataset.title+' · ':'')+'核对并确认';
 $('action-dialog-note').textContent=card.dataset.expired==='true'?'该预览已过期，未执行任何操作；请按当前业务状态重新生成预览。':'聊天中的确认不会执行操作；在这里勾选并确认后才会提交。';
 $('action-dialog-body').replaceChildren(card);openActionId=id;
 if(dialog.showModal&&!dialog.open)dialog.showModal();else dialog.setAttribute('open','');
}
function closeActionDialog(){const dialog=$('action-dialog');if(!dialog)return;openActionId=null;$('action-dialog-body').replaceChildren();if(dialog.open&&dialog.close)dialog.close();else dialog.removeAttribute('open');}
function updateActionHistory(){
 const history=$('history-actions').children.length,expired=$('expired-actions').children.length;
 $('action-history').hidden=history===0;$('action-history-summary').textContent='历史记录 · 已完成或已取消（'+history+' 条）';
 $('action-expired').hidden=expired===0;$('action-expired-summary').textContent='已过期预览 · 不可再确认（'+expired+' 条）';
 if(!$('actions').querySelector('.action-row')&&!$('actions').querySelector('.empty'))$('actions').append(node('p','暂无待处理操作。生成预览后，可在这里核对并确认。','empty'));
 if(!expired)$('expired-actions').replaceChildren();
}
function showAction(a){
 const box=node('article',undefined,'action');box.dataset.actionId=a.id;box.dataset.status=a.status;
 const expired=actionExpired(a);if(expired)box.dataset.expired='true';
 box.append(node('h3',actionTitle(a)),node('span',expired?'预览已过期 · 未执行':statusLabel[a.status]||a.status,'status'));
 const nextStep=expired?(a.status==='AUTHORIZED'?'授权已过期，尚未提交，本次不会执行。请按当前业务状态重新生成预览。':'预览已过期，未执行任何操作。请按当前业务状态重新生成预览。'):a.status==='AWAITING_AUTH'?'请先核对以下信息，再勾选确认。聊天中的确认不会执行操作。':a.status==='AUTHORIZED'?'授权已记录，尚未提交。请核对最新状态后继续。':a.status==='CANCELLED'?'本操作已取消。':a.status==='VERIFIED_SUCCESS'?receiptMessage(a):a.status==='VERIFIED_FAILED'?'本操作未完成，请展开详情查看原因。':'请先查询原操作的最新状态，勿重复创建相同操作。';
 const next=node('p',nextStep,'action-next-step');next.setAttribute('role','status');box.append(next);
 const dl=node('dl');const details=[];
 if(a.source_id)details.push(['来源账户',accountLabels.get(a.source_id)||a.source_id]);if(a.recipient_id)details.push(['收款对象',a.recipient_label||recipientLabel(a.recipient_id)]);if(a.amount_minor!==undefined)details.push(['金额',money(a.amount_minor)]);if(a.charge_amount_minor!==undefined)details.push(['单期金额',money(a.charge_amount_minor)]);if(a.fee_minor!==undefined)details.push(['手续费',money(a.fee_minor)]);if(a.first_date)details.push(['首次日期',a.first_date],['结束日期',a.end_date],['执行规则','每月 '+a.day_of_month+' 日（短月按月末） · '+a.timezone]);if(a.billing_interval)details.push(['收费周期',billingLabel(a.billing_interval)]);if(a.renewal_date)details.push(['下次约定扣费',a.renewal_date]);if(a.merchant)details.push(['代扣商户',a.merchant],['影响',a.scope]);for(const[k,v]of details)dl.append(node('dt',k),node('dd',v));box.append(dl);
 const technical=node('details',undefined,'action-technical');technical.append(node('summary','查看操作详情'));const metadata=node('dl');for(const[k,v]of [['操作编号',a.id],['回执编号',a.receipt_id],['状态说明',a.error_code]])if(v)metadata.append(node('dt',k),node('dd',v));technical.append(metadata);box.append(technical);
 const controls=node('div',undefined,'controls');
 const security=a.security;
 if(security&&!['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(a.status)){const labels={GREEN:'核对查询范围',YELLOW:'需确认本次操作',RED:'需额外身份验证'};const risk=node('div',undefined,'risk-level risk-'+security.riskLevel.toLowerCase());risk.append(node('strong',labels[security.riskLevel]||security.riskLevel),node('p',security.riskLevel==='RED'?'本次预计日累计 '+money(security.projectedMinor)+'，超过 '+money(100000)+'，需补充验证。':'核对本次操作范围、对象与参数后确认。'));if(security.verificationMode==='LOCAL_DEMO_ONLY')risk.append(node('small','本地合成验证演示'));box.append(risk);}
 if(!expired&&(a.status==='AWAITING_AUTH'||a.status==='AUTHORIZED')){
  const storedProof=verificationProofs.get(a.id);if(storedProof&&storedProof.decisionId!==security?.decisionId)verificationProofs.delete(a.id);
  const label=node('label',undefined,'verification');const checkbox=node('input');checkbox.type='checkbox';label.append(checkbox,node('span','我已核对本次参数，并明确确认操作'));box.append(label);
  const confirm=node('button',a.kind==='QUERY_MANDATES'?'确认并查询':'确认并执行','primary');confirm.disabled=true;
  const updateConfirm=()=>confirm.disabled=!checkbox.checked||!security?.decisionId||security?.needsRefresh||(security?.riskLevel==='RED'&&a.status!=='AUTHORIZED'&&!verificationProofs.has(a.id));
  checkbox.onchange=updateConfirm;
  if(security?.riskLevel==='RED'&&a.status!=='AUTHORIZED'){
   const verification=node('div',undefined,'risk-verification');let challengeId=null;
   const challenge=node('button','发起验证');challenge.type='button';const code=node('input');code.id='verification-code-'+a.id;code.type='text';code.inputMode='numeric';code.autocomplete='one-time-code';code.maxLength=6;const codeLabel=node('label','一次性验证码');codeLabel.htmlFor=code.id;codeLabel.append(code);code.disabled=true;
   const verify=node('button','校验验证码');verify.type='button';verify.disabled=true;const status=node('p','先发起验证，再从独立本机验证台取得演示码。','hint');status.setAttribute('role','status');
   challenge.onclick=()=>perform(challenge,async()=>{try{const c=await api('/actions/'+a.id+'/verification-challenges',{payloadHash:a.payload_hash,decisionId:security.decisionId});challengeId=c.challengeId;code.disabled=false;verify.disabled=false;status.textContent='挑战编号 '+c.challengeId+' · 到期 '+new Date(c.expiresAt).toLocaleTimeString();code.focus();}catch(e){if(e.security){showAction({...a,security:e.security});}cardError(a.id,e);}});
   verify.onclick=()=>perform(verify,async()=>{try{const proof=await api('/actions/'+a.id+'/verification-challenges/'+challengeId+'/verify',{code:code.value});code.value='';code.removeAttribute('aria-invalid');verificationProofs.set(a.id,{id:proof.verificationProofId,decisionId:security.decisionId});checkbox.checked=false;updateConfirm();status.textContent='验证已通过。尚未授权或执行，请再次核对并勾选确认。';}catch(e){code.value='';code.setAttribute('aria-invalid','true');code.setAttribute('aria-describedby','error-'+a.id);cardError(a.id,e);}});
   verification.append(challenge,codeLabel,verify,status);box.append(verification);
  }
  const refreshRisk=node('button','刷新风险条件');refreshRisk.type='button';refreshRisk.onclick=()=>perform(refreshRisk,async()=>{const current=await api('/actions/'+a.id+'/risk/preview',{});verificationProofs.delete(a.id);showAction({...await api('/actions/'+a.id),security:current});});technical.append(refreshRisk);
  confirm.onclick=()=>perform(confirm,async()=>{try{
   if(a.status!=='AUTHORIZED')await api('/actions/'+a.id+'/authorize',{payloadHash:a.payload_hash,decisionId:security?.decisionId,userConfirmed:checkbox.checked,verificationProofId:verificationProofs.get(a.id)?.id||null});
   const receipt=await api('/actions/'+a.id+'/submit',{});verificationProofs.delete(a.id);applyReceipt(receipt);showAction(receipt);message(receiptMessage(receipt));await resumeAssociated(a.id);await refresh();const next=workflows.find(w=>w.steps.some(s=>s.actionId===a.id)&&w.pendingAction?.id!==a.id)?.pendingAction;window.bankUI?.revealAction(next?.id||a.id);
  }catch(e){if(e.security){verificationProofs.delete(a.id);showAction({...a,status:'AWAITING_AUTH',security:e.security});}cardError(a.id,e);}});
  const cancel=node('button','取消操作');cancel.onclick=()=>perform(cancel,async()=>{showAction(await api('/actions/'+a.id+'/cancel',{}));verificationProofs.delete(a.id);await resumeAssociated(a.id);await refresh();window.bankUI?.revealAction(a.id);});controls.append(confirm,cancel);
 }

 const check=node('button','查询最新状态');check.onclick=()=>perform(check,async()=>{const r=await api('/actions/'+a.id);showAction(r);message(receiptMessage(r));await resumeAssociated(a.id);await refresh();});if(['UNKNOWN','PENDING','AUTHORIZED'].includes(a.status))controls.append(check);else technical.append(check);box.append(controls);
 if(!expired&&faultsEnabled&&['AWAITING_AUTH','AUTHORIZED'].includes(a.status)){
  const panel=node('details',undefined,'fault-controls');panel.append(node('summary','实验故障设置（仅当前动作）'));const select=node('select');for(const [id,label]of [['NONE','正常'],['BEFORE_SUBMIT_FAILURE','银行明确拒绝'],['AFTER_COMMIT_RESPONSE_LOST','执行后丢失回执'],['PENDING_THEN_SUCCESS','先受理后完成']]){const option=node('option',label);option.value=id;select.append(option);}const label=node('label','提交故障');label.append(select);const lookup=node('input');lookup.type='checkbox';const lookupLabel=node('label',undefined,'workflow-choice');lookupLabel.append(lookup,node('span','同时模拟查单不可用'));const save=node('button','应用故障设置');save.onclick=()=>perform(save,async()=>{await api('/simulation/faults',{actionId:a.id,submitMode:select.value,lookupMode:lookup.checked?'LOOKUP_UNAVAILABLE':'NONE'});notify('该动作的实验故障已配置');});panel.append(label,lookupLabel,save);box.append(panel);
 }

 const archived=['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(a.status);actionCards.get(a.id)?.remove();
 const bucket=archived?'history-actions':expired?'expired-actions':'actions';
 if(!archived&&!expired)$('actions').querySelector('.empty')?.remove();
 actionRows.get(a.id)?.remove();actionCards.set(a.id,box);
 const row=renderActionRow(a,expired,archived);actionRows.set(a.id,row);$(bucket).prepend(row);
 if(openActionId===a.id)$('action-dialog-body').replaceChildren(box);
 updateActionHistory();window.bankUI?.filterActions();
}
async function restoreActions(){const rows=await api('/actions');for(const row of [...rows].reverse())showAction(row);}
$('action-dialog-close').onclick=()=>closeActionDialog();
$('action-dialog').addEventListener('close',()=>{openActionId=null;$('action-dialog-body').replaceChildren();});
$('action-dialog').addEventListener('click',event=>{if(event.target===$('action-dialog'))closeActionDialog();});
$('workflow-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const mandateIds=[...$('workflow-mandates').querySelectorAll('input:checked')].map(n=>n.value);if(!mandateIds.length)throw Error('请明确选定至少一项月度代扣');await api('/workflows',{month:$('workflow-month').value,mandateIds,sourceId:$('workflow-source').value,recipientId:$('workflow-recipient').value,firstDate:$('workflow-first').value,endDate:$('workflow-end').value,dayOfMonth:Number($('workflow-day').value),onlyConfirmedSavings:true});await refresh();const pending=workflows.find(w=>w.pendingAction)?.pendingAction;if(pending)window.bankUI?.revealAction(pending.id);});};
async function pollOriginalChat(text){
 if(!pendingChatRequest&&!businessConversation?.conversationId&&['LANGGRAPH_MOCK','LANGGRAPH_MODEL','FAKE_AGENT'].includes(currentAgentMode))renderBusinessContext(await api('/conversations',{}),null);
 const request=prepareContextRequest(text);
 for(let attempt=0;attempt<25;attempt++){
  try{return await api('/chat',request);}
  catch(error){
   if(!['LANGGRAPH_MOCK','LANGGRAPH_MODEL'].includes(currentAgentMode)||!['CONTEXT_PROCESSING','AGENT_RECONCILING'].includes(error.code)||attempt===24)throw error;
   $('chat-status').textContent='正在理解问题并核对业务数据，等待原请求结果…';
   await new Promise(resolve=>setTimeout(resolve,2000));
  }
 }
}
async function sendQuestion(text){
 if(!appReady){notify('正在恢复业务会话，请稍候。');return;}if(chatBusy||conversationSwitchBusy||!text)return;
 if(pendingChatRequest&&pendingChatRequest.text!==text){
  lastFailedQuestion=pendingChatRequest.text;
  $('prompt').value=text;
  $('chat-status').textContent='上次请求尚未完成，请先核对原请求。新问题已保留在输入框，不会替换原请求。';
  $('chat-retry').hidden=false;return;
 }
 chatBusy=true;lastFailedQuestion='';$('chat-retry').hidden=true;
 $('context-reset').disabled=true;$('context-new').disabled=true;renderSavedConversations();
 const button=$('chat-form').querySelector('button[type="submit"]');button.disabled=true;
 document.querySelectorAll('[data-prompt]').forEach(b=>b.disabled=true);
 $('chat-form').setAttribute('aria-busy','true');$('chat-status').textContent='正在处理问题并核对数据，请稍候…';
 if(pendingChatRequest?.text!==text)message(text,'user');if($('prompt').value.trim()===text)$('prompt').value='';
 let answerReceived=false;
 try{const r=await pollOriginalChat(text);answerReceived=true;acceptContextAnswer(r);message(r.text);setMode(r.mode);
  if(r.results?.length||Object.keys(r.data||{}).length)showData(r.data,r.results);
  (r.actions||[]).forEach(showAction);if(r.trace?.length)showEvidenceTrace(r.trace);if(r.data?.workflow)await refresh();
  const pending=(r.actions||[]).filter(a=>a.status==='AWAITING_AUTH');
  if(pending.length){const link=node('button','核对待确认操作');link.type='button';link.onclick=()=>{window.bankUI?.revealAction(pending[pending.length-1].id);};const bubble=node('div',undefined,'bubble assistant');bubble.append(link);$('messages').append(bubble);$('messages').scrollTop=$('messages').scrollHeight;}
  $('chat-status').textContent='';
 }catch(e){if(answerReceived){$('chat-status').textContent='已收到回答，但页面更新失败：'+e.message+'。请刷新页面核对任务和回执。';}else{lastFailedQuestion=text;$('chat-status').textContent='本次未获得回答：'+e.message;$('chat-retry').hidden=false;await handleContextFailure(e);}}
 finally{chatBusy=false;button.disabled=false;document.querySelectorAll('[data-prompt]').forEach(b=>b.disabled=false);$('chat-form').setAttribute('aria-busy','false');$('context-reset').disabled=!businessConversation||!!pendingChatRequest;$('context-new').disabled=false;renderSavedConversations();await refreshModelBudget();}
}
$('chat-form').onsubmit=event=>{event.preventDefault();sendQuestion($('prompt').value.trim());};
$('chat-retry').onclick=()=>sendQuestion(lastFailedQuestion);
$('prompt').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing&&event.keyCode!==229){event.preventDefault();$('chat-form').requestSubmit($('chat-form').querySelector('button[type="submit"]'));}});
const views={finance:['金融推荐','从资金用途出发，筛选方向并比较金融事实。'],users:['用户管理','统一维护联系人、储蓄目标与关联账户资料。'],cards:['卡片管理','从申请到限额与挂失，每一步都有明确确认。'],chat:['对话助手','从你的问题开始，结合业务数据继续追问。'],statement:['账单分析','先看清账单，再决定下一步。'],mandates:['代扣管理','核对授权与扣费周期，撤销前逐项确认。'],savings:['储蓄任务','把已确认的计划拆成可核验的步骤。'],actions:['操作确认台','核对预览、逐项确认并查看回执；其他页面不再混排待办。'],models:['模型管理','选择供应商、管理模型与检测连接。'],tools:['常用工具','填写对象与金额，先生成操作预览。'],audit:['实验与审计','查看合成银行记录与模拟验证结果。']};
function selectView(key){if(!views[key])return;document.querySelectorAll('.view').forEach(el=>el.hidden=el.id!=='view-'+key);document.querySelectorAll('[data-view]').forEach(b=>b.setAttribute('aria-current',b.dataset.view===key?'page':'false'));$('view-title').textContent=views[key][0];$('view-subtitle').textContent=views[key][1];}
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>selectView(b.dataset.view));const requestedView=new URLSearchParams(location.search).get('view');selectView(Object.hasOwn(views,requestedView)?requestedView:'statement');
$('accounts-toggle').onclick=()=>{const panel=$('accounts-panel');panel.hidden=!panel.hidden;$('accounts-toggle').setAttribute('aria-expanded',String(!panel.hidden));};
$('focus-chat').onclick=()=>{$('prompt').scrollIntoView({block:'center'});$('prompt').focus();};
$('open-mandates').onclick=()=>{selectView('mandates');$('mandates').scrollIntoView({block:'center'});$('mandates').querySelector('button')?.focus();};
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>{$('prompt').value=b.dataset.prompt;$('chat-form').requestSubmit($('chat-form').querySelector('button[type=submit]'));});
/** 快捷提问按服务端业务日期与真实有记录的月份生成，不在页面写死历史月份。 */
window.syncStatementPrompt=months=>{
 const current=(window.bankBusinessDate||'').slice(0,7),month=current&&months.includes(current)?current:months[0]||current;
 const button=$('prompt-statement');if(!button||!month)return;
 button.dataset.prompt='分析'+month+'账单';button.title='按当前可用账单月份 '+month+' 提问';
};
$('transfer-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const a=await api('/transfers/preview',{sourceId:$('source').value,recipientId:$('recipient').value,amountMinor:amount($('amount').value)});showAction(a);window.bankUI?.revealAction(a.id);});};
$('simulate-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const results=await api('/simulation/run-due',{date:$('simulation-date').value});results.forEach(showAction);notify(results.length?'已核对 '+results.length+' 个到期操作；请查看回执。':'该日期没有到期任务。');await refresh();});};
const startupControls=[...document.querySelectorAll('[data-prompt]'),$('open-mandates'),$('chat-form').querySelector('button[type="submit"]')];startupControls.forEach(button=>button.disabled=true);
(async()=>{try{const s=await ensureSession();message('欢迎进入 SafeBank。账单可以直接提问；查看代扣请先确认查询范围，再分析收费周期。银行操作须核对预览并逐项确认。');await refresh();await restoreActions();await initializeBusinessContext(s.user);appReady=true;window.bankUI?.init();await window.modelUI?.init();startupControls.forEach(button=>button.disabled=false);setMode(s.mode,s.model);$('context-new').disabled=false;await refreshModelBudget();}catch(e){notify('初始化失败：'+e.message);$('mode').textContent='连接失败';}})();
