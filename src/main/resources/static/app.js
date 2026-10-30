let csrf='',faultsEnabled=false,workflows=[],currentAgentMode='';
let chatBusy=false,lastFailedQuestion='',appReady=false;
// ===== v4 恢复 P1：基础声明 =====
function node(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;}
function notify(message){$('notification').textContent=message;$('notification').style.display='block';clearTimeout(notify.timer);notify.timer=setTimeout(()=>$('notification').style.display='none',6500);}
async function api(path,body){const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),110000);try{const response=await fetch('/api'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});const data=await response.json();if(!response.ok){const error=Error(data.message||data.code||'请求未完成');Object.assign(error,data);throw error;}return data;}finally{clearTimeout(timer);}}
async function perform(button,fn){if(!appReady){notify('正在恢复业务会话，请稍候。');return;}button.disabled=true;try{await fn();}catch(e){notify(e.message);}finally{if(button.isConnected)button.disabled=false;}}
function amount(value){if(!/^\d+(\.\d{1,2})?$/.test(value.trim()))throw Error('金额最多两位小数，且必须为正数');const [whole,fraction='']=value.trim().split('.');const minor=Number(whole)*100+Number(fraction.padEnd(2,'0'));if(!Number.isSafeInteger(minor)||minor<=0)throw Error('金额无效');return minor;}
const $=id=>document.getElementById(id);
const money=n=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(Number(n)/100);
const accountLabels=new Map();
const billingLabel=cycle=>({MONTHLY:'按月',QUARTERLY:'按季度',YEARLY:'按年'}[cycle]||'周期未确认');
const mandateStatusLabel=status=>({ACTIVE:'银行侧授权有效',REVOKED:'银行侧授权已撤销'}[status]||'银行侧状态未确认');
const statusLabel={AWAITING_AUTH:'待确认 · 尚未执行',AUTHORIZED:'已授权 · 尚未提交',VERIFIED_SUCCESS:'已核验完成',VERIFIED_FAILED:'已核验失败',CANCELLED:'已取消',CANCEL_REQUESTED:'已停止后续步骤 · 原动作待核实',PENDING:'银行处理中',UNKNOWN:'状态待核实',PARTIAL_SUCCESS:'部分成功 · 查看各步骤'};
let confirmedMandates=null;
const providerPresets={deepseek:{label:'DeepSeek',url:'https://api.deepseek.com',model:'deepseek-flash'},openai:{label:'OpenAI',url:'https://api.openai.com/v1',model:'gpt-4o-mini'},qwen:{label:'通义千问',url:'https://dashscope.aliyuncs.com/compatible-mode/v1',model:'qwen-plus'},kimi:{label:'Kimi',url:'https://api.moonshot.cn/v1',model:'moonshot-v1-8k'},zhipu:{label:'智谱 GLM',url:'https://open.bigmodel.cn/api/paas/v4',model:'glm-4-flash'},custom:{label:'自定义',url:'',model:''}};
let currentSettings=null;
function setMode(mode,model){currentAgentMode=mode;if(mode==='LANGGRAPH_MOCK'||mode==='LANGGRAPH_MODEL'){const real=mode==='LANGGRAPH_MODEL';const provider=(currentSettings&&currentSettings.providerLabel)||'DeepSeek';$('mode').textContent=real?provider+' · 真实模型':'Mock · 免费模拟';$('model-info').textContent=real?'模型：'+model+' · 消耗 API 额度 · Java 核验业务事实与权限':'当前为离线模拟模型，不消耗 API 额度；可在左侧「模型设置」切换为真实模型。';paintModePill(real?'deepseek':'mock');return;}if(mode==='FAKE_AGENT'){$('mode').textContent='网关协议模拟';$('model-info').textContent='当前连接模拟 Python 服务，验证只读工具、权限和恢复协议；未调用大模型。';return;}$('mode').textContent=mode==='OFFLINE_DEMO'?'离线工具演示':mode==='CONNECTION_VERIFIED'?'模型连接已验证':'模型已配置 · 未验证';$('model-info').textContent=mode==='OFFLINE_DEMO'?'未设置模型密钥。对话采用有限规则匹配，联合任务可通过表单创建，银行操作执行于本地模拟环境。':'模型：'+model+' · '+(mode==='CONNECTION_VERIFIED'?'本次进程已获得有效响应':'尚未获得有效连接响应')+' · 写操作须逐项授权';}
function settingsModeLabel(mode){return mode==='deepseek'?'真实模型（消耗额度）':'Mock · 免费模拟（不消耗额度）';}
function paintModePill(mode){const pill=$('mode-pill');if(!pill)return;const real=mode==='deepseek';const provider=(currentSettings&&currentSettings.providerLabel)||'DeepSeek';pill.classList.toggle('deepseek',real);pill.textContent='当前模式：'+(real?provider+' · 真实模型（消耗额度）':'Mock · 免费模拟（不消耗额度）');}
function currentProvider(){return $('settings-provider').value||'deepseek';}
function syncProviderFields(){const provider=currentProvider();$('settings-url-label').hidden=provider==='deepseek';if(provider!=='custom'&&!$('settings-url').value.trim())$('settings-url').value=providerPresets[provider].url;}
function renderSettings(settings){
 currentSettings=settings;
 document.querySelectorAll('input[name="model-mode"]').forEach(input=>input.checked=input.value===settings.mode);
 $('settings-provider').value=settings.provider||'deepseek';
 $('settings-url').value=settings.baseUrl||'';
 syncProviderFields();
 $('settings-model').value=settings.modelName||'deepseek-flash';$('settings-key').value='';
 $('settings-key').placeholder=settings.keyConfigured?'已配置 '+settings.keyMasked+'，留空表示不修改':'未配置，请填写你自己的 API Key';
 $('settings-key-hint').textContent=settings.keyConfigured?'已保存的 Key 只显示末 4 位；密钥仅保存在本机运行卷，切换模式不会上传。':'密钥仅保存在本机运行卷，不会出现在日志、报告或提交记录里。';
 $('settings-current').textContent='当前生效：'+(settings.providerLabel||settings.provider)+' · '+settings.modelName+(settings.baseUrl?' · '+settings.baseUrl:'')+(settings.keyConfigured?' · API Key 已配置':' · 未配置 API Key');
 paintModePill(settings.mode);
 setMode(settings.mode==='deepseek'?'LANGGRAPH_MODEL':'LANGGRAPH_MOCK',settings.modelName||'deepseek-flash');
}
async function loadModelSettings(){try{renderSettings(await api('/model-settings'));return currentSettings;}catch(e){$('settings-current').textContent='无法读取模型设置：'+e.message;return null;}}
function settingsBody(includeKey){
 const checked=document.querySelector('input[name="model-mode"]:checked');const provider=currentProvider();const preset=providerPresets[provider];
 const modelName=$('settings-model').value.trim()||(preset&&preset.model)||'deepseek-flash';
 const url=$('settings-url').value.trim()||(preset&&preset.url)||'';
 if(provider==='custom'&&!url)throw Error('自定义厂商需要填写接口地址（Base URL）');
 const body={mode:checked?checked.value:'mock',provider,modelName};if(url)body.baseUrl=url;
 const key=$('settings-key').value.trim();if(includeKey&&key)body.apiKey=key;return body;
}
async function refresh(){
 const [accounts,schedules,audit,taskList]=await Promise.all([api('/accounts'),api('/schedules'),api('/audit'),api('/workflows')]);
 workflows=taskList;
 for(const account of accounts)accountLabels.set(account.id,account.label);
 $('accounts').replaceChildren(...accounts.map(a=>{const n=node('article',undefined,'account');n.append(node('p',a.label+' · 合成账户'),node('strong',money(a.balance_minor)));return n;}));
 renderMandates(confirmedMandates);
 $('schedules').replaceChildren(...schedules.map(s=>node('p','已登记：每月 '+s.day_of_month+' 日 · '+money(s.amount_minor)+' · 首次 '+s.first_date,'hint')));
 $('audit').textContent=JSON.stringify(audit,null,2);
 renderWorkflows();
}
// ===== P1 结束 =====
const actionCards=new Map(),verificationProofs=new Map(),recordSnapshots=new Map();
const layeredActions=new Set(),inFlight=new Set(),unverified=new Set(),submitEpochs=new Map(),layerStash=document.createElement('div');let layerCurrentId='';
function claimSubmit(id){if(conflicted(id))return false;inFlight.add(id);submitEpochs.set(id,(submitEpochs.get(id)||0)+1);return true;}
function releaseSubmit(id){inFlight.delete(id);unverified.delete(id);submitEpochs.set(id,(submitEpochs.get(id)||0)+1);}function unverifiedSubmit(id){inFlight.delete(id);unverified.add(id);submitEpochs.set(id,(submitEpochs.get(id)||0)+1);}function conflicted(id){return inFlight.has(id)||unverified.has(id);}
function layerBody(){return $('confirm-layer-body');}
function updateActionSummary(a){
 if(!a||!a.id)return;
 const host=$('actions');if(host)host.querySelector('[data-summary-for="'+a.id+'"]')?.remove();
}function cardError(id,error){const card=actionCards.get(id);if(!card)return;let target=card.querySelector('.risk-error');if(!target){target=node('p',undefined,'risk-error');target.id='error-'+id;target.setAttribute('role','alert');target.tabIndex=-1;card.prepend(target);}target.textContent=error.message+(error.retryAt?' · 可重试时间 '+new Date(error.retryAt).toLocaleString():'');target.focus();}
// ===== v4 恢复 P2：续费/代扣/回执 =====
function renderMandates(mandates){
 if(mandates===null){for(const id of ['mandates','workflow-mandates']){const description=node('p','读取本人银行侧代扣列表前，请确认查询范围。','hint');const query=node('button','确认查询范围');query.type='button';query.onclick=()=>perform(query,async()=>{openConfirmLayer(await createMandateQueryIntent());$('actions').scrollIntoView({block:'start'});});$(id).replaceChildren(description,query);}return;}
 const attentionOf=m=>{if(m.status==='REVOKED')return '已撤销';if(m.attention)return m.attention;if(m.status!=='ACTIVE')return '状态未确认';if(!m.renewal_date)return '日期未知';const kind=renewalNotice(m.renewal_date,m.status,mandateBusinessDate).kind;if(kind==='past')return '记录日期已过待核对';if(kind==='today'||kind==='soon')return '临近（7天内）';return '远期';};
 const urgentOf=m=>['临近（7天内）','记录日期已过待核对','日期未知'].includes(attentionOf(m));
 const shortTag=m=>{const attention=attentionOf(m);if(attention==='临近（7天内）'){const days=m.days_to_renewal;return typeof days==='number'?(days<=0?'今天到期':days+' 天后'):'7 天内';}if(attention==='记录日期已过待核对')return '已过期记录';if(attention==='日期未知')return '日期未知';if(attention==='已撤销')return '已撤销';if(attention==='状态未确认')return '状态未确认';return '未到期';};
 const mandateRow=m=>{const n=node('div',undefined,'mandate');const info=node('div',m.merchant);info.append(node('small',money(m.amount_minor)+' · '+billingLabel(m.billing_interval)));const attention=attentionOf(m);const tagClass=attention==='已撤销'?'mandate-tag mandate-tag-revoked':urgentOf(m)?'mandate-tag mandate-tag-due':'mandate-tag';info.append(node('span',shortTag(m),tagClass));n.append(info);const renewal=mandateRenewalLine(m,{businessDate:mandateBusinessDate,node});if(renewal)n.append(renewal);if(m.status==='ACTIVE'){const b=node('button','预览撤销');b.onclick=()=>perform(b,async()=>{openConfirmLayer(await api('/mandates/preview',{mandateId:m.id}));$('actions').scrollIntoView({block:'start'});});n.append(b);}return n;};
 if(!mandates.length)$('mandates').replaceChildren(node('p','本次查询没有返回银行侧代扣授权。','empty'));
 else{const groups=[['即将到期',mandates.filter(m=>m.status==='ACTIVE'&&attentionOf(m)==='临近（7天内）'),'7 天内到续费日，建议现在处理'],['未到期',mandates.filter(m=>m.status==='ACTIVE'&&attentionOf(m)==='远期'),'远期，无需动作'],['日期需核对',mandates.filter(m=>m.status==='ACTIVE'&&attentionOf(m)==='记录日期已过待核对'),'记录的续费日已过，授权仍有效，可能已扣款或记录没更新'],['日期缺失',mandates.filter(m=>m.status==='ACTIVE'&&attentionOf(m)==='日期未知'),'没有记录续费日，无法判断是否临近'],['已撤销',mandates.filter(m=>m.status==='REVOKED'),'银行侧授权已解除，不是“过期”'],['状态未确认',mandates.filter(m=>m.status!=='ACTIVE'&&m.status!=='REVOKED'),'银行侧状态未确认']];const present=groups.filter(([,items])=>items.length);const count=present.map(([title,items])=>title+' '+items.length).join(' · ');const legend=node('details',undefined,'hint');legend.append(node('summary','这些状态怎么看？'));for(const [, ,note] of present)legend.append(node('p','· '+note));const blocks=[node('p','共 '+mandates.length+' 项：'+count,'hint'),legend];for(const [title,items,note] of present){const head=node('h3',title+'（'+items.length+'）','mandate-group');head.append(node('small',' · '+note,'hint'));blocks.push(head);for(const m of items)blocks.push(mandateRow(m));}$('mandates').replaceChildren(...blocks);}
 if(mandates.length){const followup=node('div',undefined,'controls');for(const [label,text] of [['分析收费周期','说明当前代扣的约定收费周期，区分按月、按季度和未确认的周期。'],['核对有效月付','只看目前明确月付的有效授权，列服务及金额，不要把已撤销的算进去。']]){const button=node('button',label);button.type='button';button.dataset.prompt=text;button.disabled=chatBusy;button.onclick=()=>{$('prompt').scrollIntoView({block:'center'});sendQuestion(text);};followup.append(button);}const dateHint=mandateBusinessState==='ready'&&mandateBusinessDate?node('p','依据业务日期 '+mandateBusinessDate+' 计算剩余天数；依据银行侧记录，不代表已扣款。','hint'):(mandateBusinessState==='failed'?node('p','暂不能判断是否临近：业务日期不可用。','hint'):null);$('mandates').append(...(dateHint?[dateHint]:[]),node('p','查询已确认。可继续分析，或先生成撤销预览；预览不会改变授权。','hint'),followup);}
 const selected=new Set([...$('workflow-mandates').querySelectorAll('input:checked')].map(n=>n.value));
 $('workflow-mandates').replaceChildren(...mandates.map(m=>{const label=node('label',undefined,'workflow-choice');const check=node('input');check.type='checkbox';check.value=m.id;check.disabled=m.status!=='ACTIVE'||m.billing_interval!=='MONTHLY';check.checked=selected.has(m.id)&&!check.disabled;const cycle={MONTHLY:'/月',QUARTERLY:'/季度',YEARLY:'/年'}[m.billing_interval]||' · 周期未知';label.append(check,node('span',m.merchant+' '+money(m.amount_minor)+cycle+(check.disabled?' · 不可新增选择':'')));return label;}));
 const again=node('button','重新查询');again.type='button';again.onclick=()=>perform(again,async()=>openConfirmLayer(await createMandateQueryIntent()));$('mandates').append(again);
}
let mandateBusinessDate='',mandateBusinessState='idle',mandateDateSequence=0;
async function refreshMandateBusinessDate(){
 const sequence=++mandateDateSequence;mandateBusinessDate='';mandateBusinessState='loading';
 if(confirmedMandates)renderMandates(confirmedMandates);
 try{const summary=await api('/risk/summary');if(sequence!==mandateDateSequence)return;const value=summary&&summary.businessDate?summary.businessDate:'';mandateBusinessDate=renewalDayValue(value)===null?'':value;mandateBusinessState=mandateBusinessDate?'ready':'failed';}
 catch{if(sequence!==mandateDateSequence)return;mandateBusinessDate='';mandateBusinessState='failed';}
 if(sequence===mandateDateSequence&&confirmedMandates)renderMandates(confirmedMandates);
}
function applyReceipt(receipt){
 if(receipt.kind==='QUERY_MANDATES'&&Array.isArray(receipt.data)){confirmedMandates=receipt.data;mandateBusinessDate='';mandateBusinessState='loading';mandateDateSequence++;renderMandates(confirmedMandates);notify('代扣查询已确认，可回到右侧对话继续询问收费周期或取消事项。');$('chat-status').textContent='查询已确认，请在对话中继续提问。';refreshMandateBusinessDate();}
 if(receipt.kind==='REVOKE'&&receipt.status==='VERIFIED_SUCCESS'&&confirmedMandates){confirmedMandates=confirmedMandates.map(m=>m.id===receipt.mandate_id?{...m,status:'REVOKED',attention:'已撤销',days_to_renewal:null}:m);renderMandates(confirmedMandates);notify('银行侧授权已撤销，数据库已更新；列表快照已同步为已撤销，如需核对完整最新状态请重新确认查询。');}
}
// --- renewal-notice:begin （纯逻辑，可被离线测试单独加载） ---
const RENEWAL_DAY_MS=86400000;
function renewalDayValue(text){
 if(typeof text!=='string')return null;
 const match=/^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
 if(!match)return null;
 const year=Number(match[1]),month=Number(match[2]),day=Number(match[3]);
 if(month<1||month>12||day<1||day>31)return null;
 const ms=Date.UTC(year,month-1,day);
 const date=new Date(ms);
 if(date.getUTCFullYear()!==year||date.getUTCMonth()!==month-1||date.getUTCDate()!==day)return null;
 return Math.round(ms/RENEWAL_DAY_MS);
}
function renewalNotice(renewalDate,status,businessDate){
 const due=renewalDayValue(renewalDate);
 if(status!=='ACTIVE')return {kind:'inactive',dateText:due===null?'':renewalDate,note:''};
 if(due===null)return {kind:'unknown',dateText:'',note:'暂无可靠续费日期'};
 const today=renewalDayValue(businessDate);
 if(today===null)return {kind:'business-unknown',dateText:renewalDate,note:'暂不能判断是否临近'};
 const days=due-today;
 if(days<0)return {kind:'past',dateText:renewalDate,note:'记录日期已过，请重新核对'};
 if(days===0)return {kind:'today',dateText:renewalDate,note:'今天到期，请核对续费安排'};
 if(days<=7)return {kind:'soon',dateText:renewalDate,note:'还有 '+days+' 天到期'};
 return {kind:'later',dateText:renewalDate,note:''};
}
function mandateRenewalLine(mandate,ctx){
 if(!mandate)return null;
 const notice=renewalNotice(mandate.renewal_date,mandate.status,ctx&&ctx.businessDate);
 const parts=[];
 if(notice.dateText)parts.push('记录续费日 '+notice.dateText);
 if(notice.note)parts.push(notice.note);
 if(!parts.length)return null;
 const urgent=notice.kind==='today'||notice.kind==='soon';
 return ctx.node('small',parts.join(' · '),urgent?'renewal-note renewal-due':'renewal-note');
}
// --- renewal-notice:end ---
// ===== P2 结束 =====
function renderWorkflows(){
 $('workflows').replaceChildren(...workflows.map(w=>{
  const box=node('article',undefined,'workflow-item');box.dataset.workflowId=w.id;
  box.append(node('h3','联合任务 · 版本 '+w.version+' · '+(statusLabel[w.state]||w.state)),node('p','账单 '+w.request.month+' → '+w.request.recipientId+' · 预计月度节省 '+money(w.estimatedSavingMinor)+'（仅计本任务已核验撤销）','hint'));
  const list=node('ol',undefined,'workflow-steps');for(const step of w.steps){const item=node('li',(step.kind==='REVOKE'?'撤销 '+step.key.substring(7):'登记月度储蓄')+' · '+(statusLabel[step.status]||step.status)+' · v'+step.inputVersion+(step.errorCode?' · '+step.errorCode:''));const inspect=node('button','查看动作');inspect.type='button';inspect.onclick=()=>perform(inspect,async()=>{showAction(await api('/actions/'+step.actionId));$('actions').scrollIntoView({block:'start'});});item.append(inspect);list.append(item);}
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
  if(w.pendingAction&&w.pendingAction.id){const inspect=node('button','查看当前确认项');inspect.onclick=()=>{showAction(w.pendingAction);$('actions').scrollIntoView({block:'start'});};controls.append(inspect);showAction(w.pendingAction);}
  if(!['VERIFIED_SUCCESS','PARTIAL_SUCCESS','CANCELLED','CANCEL_REQUESTED'].includes(w.state)){
   const cancel=node('button','取消后续任务');cancel.onclick=()=>perform(cancel,async()=>{await api('/workflows/'+w.id+'/cancel',{version:w.version});await refresh();await restoreActions();});controls.append(cancel);
   const details=node('details');details.append(node('summary','修改后续参数（原待确认项将失效）'));
   const form=node('form',undefined,'workflow-edit');const target=node('select');for(const [id,label] of [['travel-recipient','我的旅游账户'],['zhang-a','张三 · 同学'],['zhang-b','张三 · 同事']]){const option=node('option',label);option.value=id;target.append(option);}target.value=w.request.recipientId;
   const first=node('input'),end=node('input');first.type=end.type='date';first.value=w.request.firstDate;end.value=w.request.endDate;first.required=end.required=true;
   for(const [caption,input] of [['目标',target],['首次日期',first],['结束日期',end]]){const label=node('label',caption);label.append(input);form.append(label);}
   const save=node('button','保存新版本');form.append(save);form.onsubmit=e=>{e.preventDefault();perform(save,async()=>{await api('/workflows/'+w.id+'/revise',{version:w.version,request:{...w.request,recipientId:target.value,firstDate:first.value,endDate:end.value,dayOfMonth:Number(first.value.split('-')[2])}});await refresh();await restoreActions();});};details.append(form);box.append(details);
  }
  box.append(controls);return box;
 }));
}
function message(text,role='assistant'){const n=node('div',undefined,'bubble '+role);n.append(node('small',role==='user'?'你的需求':'SafeBank'),node('div',text));$('messages').append(n);$('messages').scrollTop=$('messages').scrollHeight;}
function showEvidenceTrace(trace){
 const names={get_statement:'查询账单',get_accounts:'查询余额',list_mandates:'查询银行代扣',detect_recurring_payments:'查询重复扣费候选',resolve_recipient:'核对收款人',calculate_business_metric:'计算业务数据',answer_customer:'根据问题组织回答',get_action_status:'核对银行动作状态',list_transfer_schedules:'查询定时计划',preview_transfer:'生成转账确认卡',preview_revoke:'生成代扣撤销确认卡',preview_schedule:'生成计划确认卡',start_savings_workflow:'创建逐项授权任务',get_workflow:'查询任务进度'};
 const details=node('details',undefined,'bubble assistant');details.append(node('summary','查看本轮查询依据'));const list=node('ul');
 for(const entry of trace){const tool=entry.split(' / ')[0];list.append(node('li',entry.includes('ANSWER_NOT_GROUNDED')?'核对并修正回答依据':names[tool]||'检查业务参数'));}
 details.append(list);$('messages').append(details);$('messages').scrollTop=$('messages').scrollHeight;
}
let yearMonthMount=null;
function dataContent(data){
  const box=node('section',undefined,'result-block');
 if(data&&data.matched_count!==undefined&&data.direction_code&&typeof filterSummary==='function'){
  const summary=filterSummary(data,money),rows=filterRows(data);
  box.append(node('h3',summary.title));
  const totals=node('div',undefined,'statement-totals');
  const totalCard=node('div',undefined,'statement-total');totalCard.append(node('span',summary.totalLabel),node('strong',summary.total));totals.append(totalCard);
  const countCard=node('div',undefined,'statement-total');countCard.append(node('span',summary.matchesLabel),node('strong',summary.matches));totals.append(countCard);
  box.append(totals,node('p',summary.note,'hint'));
  if(summary.empty)box.append(node('p',summary.emptyText,'hint'));
  else{
   if(!summary.income&&Object.keys(data.categories||{}).length){box.append(node('h3','支出分类'));for(const[category,total]of Object.entries(data.categories).sort((a,b)=>b[1]-a[1])){const row=node('div',undefined,'category-row');row.append(node('span',category),node('strong',money(total)));const track=node('div',undefined,'category-track'),bar=node('div',undefined,'category-bar');bar.style.width=(data.expense_minor?Math.min(100,Number(total)/data.expense_minor*100):0)+'%';track.append(bar);row.append(track);box.append(row);}}
   const details=node('details');details.open=rows.length<=10;details.append(node('summary','逐笔明细 · '+rows.length+' 笔'));const list=node('div');
   for(const row of rows)list.append(node('p',row.booked+' · '+row.merchant+' · '+row.direction+' · '+money(row.magnitude),'transaction-row'));
   details.append(list);box.append(details);
  }
 }else if(data&&data.year!==undefined&&Array.isArray(data.months)){
  box.append(node('h3',data.year+' 年度账单'));
  const totals=node('div',undefined,'statement-totals');for(const[label,value]of [['收入',data.income_minor],['支出',data.expense_minor]]){const card=node('div',undefined,'statement-total');card.append(node('span',label),node('strong',money(value)));totals.append(card);}box.append(totals,node('p','入账包含退款；内部转账不计入收支。净收支 '+money(data.income_minor-data.expense_minor),'hint'));
  if(!data.record_count)box.append(node('p','这个年份没有查询到已记录交易。','hint'));
  else{const monthly=node('div');monthly.append(node('h3','按月 · 点击月份查看当月明细'));
   if(typeof createYearMonths==='function'){yearMonthMount=yearMonthMount||createYearMonths({node,money,api});yearMonthMount(monthly,data);}
   else for(const row of data.months)monthly.append(node('p',row.month+' · 支出 '+money(row.expense_minor)+' · 收入 '+money(row.income_minor)+' · '+row.transaction_count+' 笔','transaction-row'));
   monthly.append(node('h3','消费分类'));
   for(const[category,total]of Object.entries(data.categories||{}).sort((a,b)=>b[1]-a[1])){const row=node('div',undefined,'category-row');row.append(node('span',category),node('strong',money(total)));const track=node('div',undefined,'category-track'),bar=node('div',undefined,'category-bar');bar.style.width=(data.expense_minor?Math.min(100,Number(total)/data.expense_minor*100):0)+'%';track.append(bar);row.append(track);monthly.append(row);}
   box.append(monthly);}
  if(data.internal_transfer_minor)box.append(node('p','已排除本人账户内部转账 '+money(data.internal_transfer_minor),'hint'));
  const cov=data.coverage||{};const note=[cov.first_booked_on&&cov.last_booked_on?'已记录交易日期 '+cov.first_booked_on+' 至 '+cov.last_booked_on+'（仅表示本轮取到的记录，不代表数据完整）':'',cov.year_in_progress?'当年未结束，统计截至业务日期 '+(cov.business_date||''):'',cov.beyond_business_date?'存在晚于业务日期的记录，按实际记录日期统计':''].filter(Boolean).join('；');
  if(note)box.append(node('p',note,'hint'));
 }else if(data&&data.expense_minor!==undefined){
  box.append(node('h3',data.month+' 账单'));
  const totals=node('div',undefined,'statement-totals');for(const[label,value]of [['收入',data.income_minor],['支出',data.expense_minor]]){const card=node('div',undefined,'statement-total');card.append(node('span',label),node('strong',money(value)));totals.append(card);}box.append(totals,node('p','入账包含退款；内部转账不计入收支。净收支 '+money(data.income_minor-data.expense_minor),'hint'),node('h3','支出分类'));
  for(const[category,total]of Object.entries(data.categories).sort((a,b)=>b[1]-a[1])){const row=node('div',undefined,'category-row');row.append(node('span',category),node('strong',money(total)));const track=node('div',undefined,'category-track'),bar=node('div',undefined,'category-bar');bar.style.width=(data.expense_minor?Math.min(100,Number(total)/data.expense_minor*100):0)+'%';track.append(bar);row.append(track);box.append(row);}
  if(!data.transactions.length)box.append(node('p','这个月份没有查询到交易记录。','hint'));
  if(data.internal_transfer_minor)box.append(node('p','已排除本人账户内部转账 '+money(data.internal_transfer_minor),'hint'));
  const details=node('details');details.open=data.transactions.length<=6;details.append(node('summary','交易明细 · '+data.transactions.length+' 笔'));const list=node('div');for(const t of data.transactions){const row=node('p',t.booked_on+' · '+t.merchant+' · '+money(t.amount_minor),'transaction-row');list.append(row);}details.append(list);box.append(details);
 }else if(Array.isArray(data)&&data.length&&data[0].balance_minor!==undefined){box.append(node('h3','查询到的账户余额'));for(const a of data)box.append(node('p',a.label+'：'+money(a.balance_minor),'result-total'));}
 else if(Array.isArray(data)&&data.length&&data[0].merchant!==undefined){box.append(node('h3',data[0].occurrences!==undefined?'周期扣费候选':'银行侧代扣'));for(const m of data){const text=m.occurrences!==undefined?m.merchant+' · '+m.occurrences+'次 · '+money(m.min_amount_minor)+' 至 '+money(m.max_amount_minor):m.merchant+' · '+money(m.amount_minor)+' · '+billingLabel(m.billing_interval)+' · '+mandateStatusLabel(m.status);box.append(node('p',text,'transaction-row'));}}
 else if(data&&data.workflow){box.append(node('h3','联合任务已保存'),node('p','任务编号 '+data.workflow.id+' · '+(statusLabel[data.workflow.state]||data.workflow.state),'hint'));}
 else if(data&&data.code){box.append(node('p',data.message+'（'+data.code+'）','result-error'));}
 if(data&&Object.keys(data).length){const details=node('details');details.append(node('summary','原始银行数据'));details.append(node('pre',JSON.stringify(data,null,2)));box.append(details);}
 return box;
}
function showData(data,results){$('tool-result').replaceChildren();if(results&&results.length){for(const result of results)$('tool-result').append(dataContent(result.data));}else if(data&&Object.keys(data).length)$('tool-result').append(dataContent(data));}
function receiptMessage(r){if(r.status!=='VERIFIED_SUCCESS')return (statusLabel[r.status]||r.status)+'。请以查单结果为准，勿重新创建相同动作。';if(r.kind==='QUERY_MANDATES')return Array.isArray(r.data)?'本次代扣查询已完成；查询没有转账或记账。':'这是原查询回执，重新查询需再次确认范围。';return r.kind==='SCHEDULE'?'定时计划已登记，尚未发生转账。':r.kind==='REVOKE'?'银行侧代扣已撤销，商户合同状态未知，余额没有因此增加。':'转账已完成，并核验双边账务分录。';}
async function resumeAssociated(actionId){for(const w of workflows)if(w.steps.some(step=>step.actionId===actionId)&&!['CANCELLED','VERIFIED_SUCCESS','PARTIAL_SUCCESS'].includes(w.state))await api('/workflows/'+w.id+'/resume',{version:w.version});}
function updateActionHistory(){
 const countOf=host=>host?[...host.children].filter(x=>!String(x.className||'').split(' ').includes('empty')).length:0;
 const history=$('history-actions');const historyCount=countOf(history);
 $('action-history').hidden=historyCount===0;
 $('action-history-summary').textContent='处理记录 · 已完成、已取消、已失败或已过期（'+historyCount+' 条）';
 const pending=$('actions');const pendingCount=countOf(pending);
 if(!pending)return;
 if(pendingCount===0){if(!pending.querySelector('.empty'))pending.append(node('p','暂无待确认操作。已取消、已完成或已过期的都在下方处理记录。','empty'));}
 else pending.querySelector('.empty')?.remove();
}
// 页面打开期间如果有单子到点过期，就地把它挪进处理记录，不需要刷新。
function sweepExpiredRecords(){
 const now=Date.now();
 for(const [id,a] of recordSnapshots){
  const archived=recordArchived(a,now);
  const inPending=$('actions')?.querySelector('[data-record-id="'+id+'"]');
  const inHistory=$('history-actions')?.querySelector('[data-record-id="'+id+'"]');
  if((archived&&inPending)||(!archived&&inHistory&&!inPending))renderRecord(a);
 }
}// ===== v4 恢复 P3：视图/恢复/聊天 =====
let confirmLayerFocus=null;
const views={statement:['账单分析','先看清账单，再决定下一步。'],mandates:['代扣管理','核对授权与扣费周期，撤销前逐项确认。'],savings:['储蓄任务','把已确认的计划拆成可核验的步骤。'],tools:['常用工具','填写对象与金额，先生成转账预览。'],records:['任务记录','查看并管理待确认操作与历史回执。'],audit:['实验与审计','查看合成银行记录与模拟验证结果。'],settings:['模型设置','切换免费 Mock 与真实 DeepSeek 模型，立即生效。']};
async function restoreActions(){
 const audit=await api('/audit');
 const ids=[...new Set(audit.map(row=>row.action_id))].slice(0,12);
 const kinds=['TRANSFER','REVOKE','SCHEDULE','QUERY_MANDATES'];
 for(const id of ids){
  try{
   const record=await api('/actions/'+encodeURIComponent(id)+'/record');
   if(record&&record.id===id&&kinds.includes(record.kind)&&record.status)renderRecord(record);
   else markRecordUnavailable(id);
  }catch(e){markRecordUnavailable(id);}
 }
}
async function acceptRecoveredAnswer(r){
 if(!r||!r.text)return false;
 if(pendingChatRequest&&pendingChatRequest.text)message(pendingChatRequest.text,'user');
 acceptContextAnswer(r);message(r.text);setMode(r.mode,'deepseek-v4-flash');
 if(r.results?.length||Object.keys(r.data||{}).length)showData(r.data,r.results);
 (r.actions||[]).forEach(showAction);if(r.data?.workflow)await refresh();
 return true;
}
async function sendQuestion(text){
 if(!appReady){notify('正在恢复业务会话，请稍候。');return;}if(chatBusy||!text)return;
 if(pendingChatRequest&&pendingChatRequest.text!==text){
  lastFailedQuestion=pendingChatRequest.text;
  $('prompt').value=text;
  $('chat-status').textContent='上次请求尚未完成，请先核对原请求。新问题已保留在输入框，不会替换原请求。';
  $('chat-retry').hidden=false;return;
 }
 chatBusy=true;lastFailedQuestion='';$('chat-retry').hidden=true;
 $('context-reset').disabled=true;
 const button=$('chat-form').querySelector('button[type="submit"]');button.disabled=true;
 document.querySelectorAll('[data-prompt]').forEach(b=>b.disabled=true);
 $('chat-form').setAttribute('aria-busy','true');$('chat-status').textContent='正在处理问题并核对数据，请稍候…';
 if(pendingChatRequest?.text!==text)message(text,'user');if($('prompt').value.trim()===text)$('prompt').value='';
 let answerReceived=false;
 try{const r=await pollOriginalChat(text);answerReceived=true;acceptContextAnswer(r);message(r.text);setMode(r.mode,'deepseek-v4-flash');
  if(r.results?.length||Object.keys(r.data||{}).length)showData(r.data,r.results);
  (r.actions||[]).forEach(showAction);if(r.trace?.length)showEvidenceTrace(r.trace);if(r.data?.workflow)await refresh();
  const pending=(r.actions||[]).filter(a=>a.status==='AWAITING_AUTH');
  if(pending.length){const last=pending[pending.length-1];const link=node('button','核对待确认操作');link.type='button';link.onclick=()=>{selectView('records');const card=actionCards.get(last.id);if(card){card.tabIndex=-1;card.focus({preventScroll:true});}$('actions').scrollIntoView({block:'center',behavior:'smooth'});};const bubble=node('div',undefined,'bubble assistant');bubble.append(link);$('messages').append(bubble);$('messages').scrollTop=$('messages').scrollHeight;}
  $('chat-status').textContent='';
 }catch(e){if(answerReceived){$('chat-status').textContent='已收到回答，但页面更新失败：'+e.message+'。请刷新页面核对任务和回执。';}else{lastFailedQuestion=text;$('chat-status').textContent='本次未获得回答：'+e.message;$('chat-retry').hidden=false;await handleContextFailure(e);}}
 finally{chatBusy=false;button.disabled=false;document.querySelectorAll('[data-prompt]').forEach(b=>b.disabled=false);$('chat-form').setAttribute('aria-busy','false');$('context-reset').disabled=!businessConversation||!!pendingChatRequest;}
}
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
// ===== P3 结束 =====
function showAction(a){
 recordSnapshots.set(a.id,a);
 const box=node('article',undefined,'action');box.dataset.actionId=a.id;box.dataset.status=a.status;
 box.append(node('h3',{TRANSFER:'转账操作',REVOKE:'撤销银行侧代扣',SCHEDULE:'创建定时转账',QUERY_MANDATES:'查询本人银行代扣'}[a.kind]||a.kind),node('span',statusLabel[a.status]||a.status,'status'));
 const nextStep=a.status==='AWAITING_AUTH'?'请先核对以下信息，再勾选确认。聊天中的确认不会执行操作。':a.status==='AUTHORIZED'?'授权已记录，尚未提交。请核对最新状态后继续。':a.status==='CANCELLED'?'本操作已取消。':a.status==='VERIFIED_SUCCESS'?receiptMessage(a):a.status==='VERIFIED_FAILED'?'本操作未完成，请展开详情查看原因。':'请先查询原操作的最新状态，勿重复创建相同操作。';
 const next=node('p',nextStep,'action-next-step');next.setAttribute('role','status');box.append(next);
 if(a.kind==='REVOKE'&&['AWAITING_AUTH','AUTHORIZED'].includes(a.status)){
  const explain=node('section',undefined,'action-explain');
  explain.append(node('p','这是什么：撤销这张银行侧代扣授权的预览。现在还没有执行，只是把要撤销的一项整理给你核对。'));
  explain.append(node('p','确认之后：银行侧这张授权会被解除（状态变为已撤销），之后这笔代扣不能再从这张授权扣款；列表会同步更新，其他项目不受影响。'));
  explain.append(node('p','不会发生：不会退款，余额不会因此增加，也不代表商户合同已经终止——商户仍可能通过其他渠道收费，需要你和商户另行核对或取消。'));
  explain.append(node('p','你需要做：核对上面的商户、单期金额和周期无误后再确认；撤销后保留凭证，若仍有扣款请与商户核对订阅状态。'));
  box.append(explain);
 }
 const dl=node('dl');const details=[];
 if(a.source_id)details.push(['来源账户',accountLabels.get(a.source_id)||a.source_id]);if(a.recipient_id)details.push(['收款对象',a.recipient_label||a.recipient_id]);if(a.amount_minor!==undefined)details.push(['金额',money(a.amount_minor)]);if(a.charge_amount_minor!==undefined)details.push(['单期金额',money(a.charge_amount_minor)]);if(a.fee_minor!==undefined)details.push(['手续费',money(a.fee_minor)]);if(a.first_date)details.push(['首次日期',a.first_date],['结束日期',a.end_date],['执行规则','每月 '+a.day_of_month+' 日 · '+a.timezone]);if(a.merchant)details.push(['代扣商户',a.merchant],['影响',a.scope]);for(const[k,v]of details)dl.append(node('dt',k),node('dd',v));box.append(dl);
 const technical=node('details',undefined,'action-technical');technical.append(node('summary','查看操作详情'));const metadata=node('dl');for(const[k,v]of [['操作编号',a.id],['回执编号',a.receipt_id],['状态说明',a.error_code]])if(v)metadata.append(node('dt',k),node('dd',v));technical.append(metadata);box.append(technical);
 const controls=node('div',undefined,'controls');
 const security=a.security;
 if(security){const labels={GREEN:'绿色 · 可自动查询',YELLOW:'黄色 · 需要明确确认',RED:'红色 · 需要验证后确认'};const risk=node('div',undefined,'risk-level risk-'+security.riskLevel.toLowerCase());risk.append(node('strong',labels[security.riskLevel]||security.riskLevel),node('p',security.riskLevel==='RED'?'本次预计日累计 '+money(security.projectedMinor)+'，超过 '+money(100000)+'，需补充验证。':'核对本次操作范围、对象与参数后确认。'));if(security.verificationMode==='LOCAL_DEMO_ONLY')risk.append(node('small','本地合成验证演示'));box.append(risk);}
 if(a.status==='AWAITING_AUTH'||a.status==='AUTHORIZED'){
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
  const refreshRisk=node('button','刷新风险条件');refreshRisk.type='button';refreshRisk.onclick=()=>perform(refreshRisk,async()=>{const current=await api('/actions/'+a.id+'/risk/preview',{});verificationProofs.delete(a.id);showAction({...await api('/actions/'+a.id),security:current});});controls.append(refreshRisk);
  confirm.onclick=()=>{if(!claimSubmit(a.id))return;let phase='authorize',settled=false;perform(confirm,async()=>{try{
   if(a.status!=='AUTHORIZED')await api('/actions/'+a.id+'/authorize',{payloadHash:a.payload_hash,decisionId:security?.decisionId,userConfirmed:checkbox.checked,verificationProofId:verificationProofs.get(a.id)?.id||null});
   phase='submit';
   const receipt=await api('/actions/'+a.id+'/submit',{});verificationProofs.delete(a.id);applyReceipt(receipt);showAction(receipt);message(receiptMessage(receipt));
   if(['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED','AWAITING_AUTH','AUTHORIZED'].includes(receipt.status))releaseSubmit(a.id);else unverifiedSubmit(a.id);
   settled=true;
   try{await resumeAssociated(a.id);await refresh();}catch{/* 已核实回执不因刷新失败改写 */}
  }catch(e){if(settled)return;if(e.security){verificationProofs.delete(a.id);showAction({...a,status:'AWAITING_AUTH',security:e.security});}cardError(a.id,e);
   unverifiedSubmit(a.id);}});};
  const cancel=node('button','取消操作');cancel.onclick=()=>{if(conflicted(a.id)){notify('该笔正在提交或结果未核实，请先核对原单，暂不能取消。');return;}if(!claimSubmit(a.id))return;perform(cancel,async()=>{try{showAction(await api('/actions/'+a.id+'/cancel',{}));verificationProofs.delete(a.id);releaseSubmit(a.id);}catch(e){unverifiedSubmit(a.id);cardError(a.id,e);}await resumeAssociated(a.id);await refresh();});};controls.append(confirm,cancel);
 }

 const check=node('button','查询最新状态');check.onclick=()=>{if(inFlight.has(a.id)){notify('当前这笔正在提交，请等结果出来再核对。');return;}perform(check,async()=>{const epoch=submitEpochs.get(a.id)||0;const r=await api('/actions/'+a.id);if((submitEpochs.get(a.id)||0)!==epoch)return;if(['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED','AWAITING_AUTH','AUTHORIZED'].includes(r.status))releaseSubmit(a.id);showAction(r);message(receiptMessage(r));await resumeAssociated(a.id);await refresh();});};controls.append(check);box.append(controls);
 if(faultsEnabled&&['AWAITING_AUTH','AUTHORIZED'].includes(a.status)){
  const panel=node('details',undefined,'fault-controls');panel.append(node('summary','实验故障设置（仅当前动作）'));const select=node('select');for(const [id,label]of [['NONE','正常'],['BEFORE_SUBMIT_FAILURE','银行明确拒绝'],['AFTER_COMMIT_RESPONSE_LOST','执行后丢失回执'],['PENDING_THEN_SUCCESS','先受理后完成']]){const option=node('option',label);option.value=id;select.append(option);}const label=node('label','提交故障');label.append(select);const lookup=node('input');lookup.type='checkbox';const lookupLabel=node('label',undefined,'workflow-choice');lookupLabel.append(lookup,node('span','同时模拟查单不可用'));const save=node('button','应用故障设置');save.onclick=()=>perform(save,async()=>{await api('/simulation/faults',{actionId:a.id,submitMode:select.value,lookupMode:lookup.checked?'LOOKUP_UNAVAILABLE':'NONE'});notify('该动作的实验故障已配置');});panel.append(label,lookupLabel,save);box.append(panel);
 }
 if(['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(a.status)){const archived=node('details');archived.append(node('summary','查看回执详情'));for(const child of [...box.children].slice(2))archived.append(child);box.append(archived);}
 const archived=['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(a.status);actionCards.get(a.id)?.remove();
 if(layeredActions.has(a.id)){if(a.id===layerCurrentId){const body=layerBody();if(body){body.append(box);actionCards.set(a.id,box);updateActionSummary(a);updateActionHistory();return;}}if(['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(a.status)){layeredActions.delete(a.id);actionCards.set(a.id,box);updateActionSummary(a);renderRecord(a);updateActionHistory();return;}layerStash.append(box);actionCards.set(a.id,box);updateActionSummary(a);updateActionHistory();return;}
 updateActionSummary(a);renderRecord(a);updateActionHistory();
}
// --- record-triage:begin ---
// 任务记录分流：待确认栏只放"还能办"的（未确认/已授权未提交/银行处理中/状态待核实且未过期），
// 已取消、已完成、已失败、已过期一律进处理记录。过期由 expires_at 派生，数据库状态不因此改写。
const RECORD_TERMINAL=['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'];
function recordExpiry(a){const raw=a&&a.expires_at?Date.parse(a.expires_at):NaN;return Number.isFinite(raw)?raw:null;}
function recordExpired(a,now){
 if(!a||!['AWAITING_AUTH','AUTHORIZED'].includes(a.status))return false;   // 已提交/已取消的没有"过期未办"语义
 const at=recordExpiry(a);const clock=Number.isFinite(now)?now:Date.now();
 return at===null||at<=clock;                                              // 有效期缺失或读不懂，一律当不能继续办理
}
function recordArchived(a,now){if(!a||!a.id)return true;return RECORD_TERMINAL.includes(a.status)||recordExpired(a,now);}
function recordHeadline(a,now){return recordExpired(a,now)?'已过期 · 尚未执行':(statusLabel[a.status]||String((a&&a.status)||''));}
// --- record-triage:end ---
let recordNavSequence=0;
function recordSummary(a){
 const kindLabel={TRANSFER:'转账',REVOKE:'代扣撤销',SCHEDULE:'定时储蓄',QUERY_MANDATES:'代扣查询'}[a.kind]||a.kind;
 const now=Date.now();const expired=recordExpired(a,now);const statusText=recordHeadline(a,now);
 const row=node('div',undefined,'action-summary');row.dataset.summaryFor=a.id;row.dataset.recordId=a.id;
 const head=node('p',kindLabel+' · '+statusText+(a.error_code?' · '+a.error_code:''),'hint');
 const detail=node('details');detail.append(node('summary','查看详情（本地记录，不查询银行）'));
 const list=node('ul');list.append(node('li','动作编号 '+a.id+'（只读记录）'));
 if(a.expires_at)list.append(node('li','有效至 '+a.expires_at));
 if(a.receipt_id)list.append(node('li','回执 '+a.receipt_id));
 if(expired)list.append(node('li','过期只表示原单不能再确认或提交；银行侧没有因此发生任何操作'));
 detail.append(list);
 if(expired)row.append(head,detail,node('p','这笔已过期，未执行。如需办理请在业务页重新发起，旧单不能延长有效期。','hint'));
 else{const open=node('button','去处理');open.type='button';open.dataset.recordHandle=a.id;open.onclick=()=>goHandleRecord(a.id);row.append(head,detail,open);}
 return row;
}
function renderRecord(a){
 if(!a||!a.id)return;
 recordSnapshots.set(a.id,a);
 for(const host of [$('actions'),$('history-actions')]){
  if(host)host.querySelector('[data-record-id="'+a.id+'"]')?.remove();
 }
 const archived=recordArchived(a,Date.now());
 const host=archived?$('history-actions'):$('actions');if(!host)return;
 host.querySelector('.empty')?.remove();
 host.prepend(recordSummary(a));
 updateActionHistory();
}
function markRecordUnavailable(id){
 const known=recordSnapshots.get(id);
 const archived=!!known&&recordArchived(known,Date.now());
 for(const host of [$('actions'),$('history-actions')]){
  if(!host)continue;
  try{host.querySelector('[data-record-id="'+id+'"]')?.remove();}catch(e){}
 }
 const host=archived?$('history-actions'):$('actions');
 if(host){
  const row=node('div',undefined,'action-summary');row.dataset.recordId=id;
  row.append(node('p','记录读取失败，未读到最新状态，请稍后再试；本行不代表最新结果。','hint'));
  host.prepend(row);
 }
 try{updateActionHistory();}catch(e){}
}function goHandleRecord(id){
 const sequence=++recordNavSequence;
 api('/actions/'+encodeURIComponent(id)+'/record').then(a=>{
  if(sequence!==recordNavSequence)return;
  if(!a||a.id!==id||!a.kind){notify('无法读取这笔操作的记录，未打开任何原单。');markRecordUnavailable(id);return;}
  const allowed=['TRANSFER','REVOKE','SCHEDULE','QUERY_MANDATES'];
  if(!allowed.includes(a.kind)){notify('这笔记录的类型无法在业务页处理（'+a.kind+'）。');return;}
  renderRecord(a);
  const terminal=['VERIFIED_SUCCESS','VERIFIED_FAILED','CANCELLED'].includes(a.status);
  const accepted=['PENDING','UNKNOWN'].includes(a.status);
  const unsubmitted=['AWAITING_AUTH','AUTHORIZED'].includes(a.status);
  const view=a.kind==='TRANSFER'?'tools':(a.kind==='SCHEDULE'?'savings':'mandates');
  selectView(view);
  if(terminal){openConfirmLayer(a);return;}
  if(accepted){openConfirmLayer(a);return;}
  if(unsubmitted){
   const expiresAt=a.expires_at?Date.parse(a.expires_at):NaN;
   if(!Number.isFinite(expiresAt)){notify('这笔操作缺少可用有效期，不能在此继续办理，请回到业务页重新发起。');return;}
   if(expiresAt<Date.now()){notify('这笔操作已过期且尚未提交，请重新发起；旧单不能在记录页延长有效期。');return;}
   openConfirmLayer(a);return;
  }
  notify('这笔记录当前状态（'+a.status+'）不能直接办理，请先核对原单。');
 }).catch(e=>{if(sequence!==recordNavSequence)return;markRecordUnavailable(id);notify('无法读取这笔操作的记录：'+(e&&e.message?e.message:'未知原因'));});
}
function openConfirmLayer(a){
 const body=layerBody();if(!body)return;
 const shown=body.querySelector('.action');
 if(shown&&shown.dataset.actionId!==a.id)layerStash.append(shown);
 layeredActions.add(a.id);layerCurrentId=a.id;confirmLayerFocus=document.activeElement;
 showAction(a);updateActionSummary(a);
 const dialog=$('confirm-layer');
 if(dialog&&!dialog.open)dialog.showModal();
}
function reopenConfirmLayer(id){
 const body=layerBody();if(!body)return;
 const shown=body.querySelector('.action');if(shown&&shown.dataset.actionId!==id)layerStash.append(shown);const recorded=actionCards.get(id);
 if(recorded&&recorded.parentNode!==body){body.append(recorded);}layerCurrentId=id;
 updateActionSummary({id});
 const dialog=$('confirm-layer');
 if(dialog&&!dialog.open)dialog.showModal();
}
function closeConfirmLayer(){
 const dialog=$('confirm-layer');
 if(dialog&&dialog.open)dialog.close();
}
function onConfirmLayerClosed(){
 const body=layerBody();const card=body?body.querySelector('.action'):null;
 if(card){
  const id=card.dataset.actionId;
  const snapshot=recordSnapshots.get(id)||{id,status:card.dataset.status||''};
  card.remove();layeredActions.delete(id);actionCards.delete(id);
  if(snapshot&&snapshot.kind)renderRecord(snapshot);
 }
 layerCurrentId='';
 updateActionHistory();
 if(confirmLayerFocus&&confirmLayerFocus.focus)confirmLayerFocus.focus();
}
function selectView(key){if(!views[key])return;document.querySelectorAll('.view').forEach(el=>el.hidden=el.id!=='view-'+key);document.querySelectorAll('[data-view]').forEach(b=>b.setAttribute('aria-current',b.dataset.view===key?'page':'false'));$('view-title').textContent=views[key][0];$('view-subtitle').textContent=views[key][1];if(key==='settings')loadModelSettings();}
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>selectView(b.dataset.view));selectView('statement');
$('settings-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const saved=await api('/model-settings',settingsBody(true));renderSettings(saved);notify('模型设置已保存，后续新对话立即使用新设置。');$('settings-status').textContent='已保存：'+settingsModeLabel(saved.mode);});};
$('settings-test').onclick=()=>perform($('settings-test'),async()=>{
 $('settings-status').textContent='正在保存并测试连接…（最多约 75 秒，测试会计 1 次模型请求）';
 try{const probe=await api('/model-settings/test',settingsBody(true));if(!probe.ok)throw Error('连接测试未通过：'+(probe.code||'未知原因')+(probe.detail?'（'+probe.detail.slice(0,180)+'）':''));const saved=await api('/model-settings',settingsBody(true));renderSettings(saved);notify('连接测试通过，模型设置已保存。');$('settings-status').textContent='连接测试通过，已保存：'+settingsModeLabel(saved.mode);}
 catch(e){$('settings-status').textContent='未保存：'+e.message;}
});
$('settings-clear').onclick=()=>perform($('settings-clear'),async()=>{if(!confirm('清除已保存的 API Key？清除后需重新填写才能使用真实模型。'))return;const saved=await api('/model-settings',{apiKey:''});renderSettings(saved);notify('已清除 API Key。');});
$('settings-provider').onchange=()=>{const provider=currentProvider();const preset=providerPresets[provider];if(preset&&provider!=='custom')$('settings-url').value=preset.url;if(preset&&preset.model)$('settings-model').value=preset.model;$('settings-url-label').hidden=provider==='deepseek';};
$('accounts-toggle').onclick=()=>{const panel=$('accounts-panel');panel.hidden=!panel.hidden;$('accounts-toggle').setAttribute('aria-expanded',String(!panel.hidden));};
const layout=document.querySelector('.app-layout');const assistantToggle=$('assistant-toggle');
function applyAssistantCollapsed(collapsed){layout.classList.toggle('assistant-collapsed',collapsed);assistantToggle.textContent=collapsed?'展开助手':'收起助手';assistantToggle.setAttribute('aria-expanded',String(!collapsed));try{localStorage.setItem('safebank.assistantCollapsed',collapsed?'1':'0');}catch(e){}}
assistantToggle.onclick=()=>applyAssistantCollapsed(!layout.classList.contains('assistant-collapsed'));
let assistantCollapsed=false;try{assistantCollapsed=localStorage.getItem('safebank.assistantCollapsed')==='1';}catch(e){}
applyAssistantCollapsed(assistantCollapsed);
$('open-mandates').onclick=()=>{selectView('mandates');$('mandates').scrollIntoView({block:'center'});$('mandates').querySelector('button')?.focus();};
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>{$('prompt').value=b.dataset.prompt;$('chat-form').requestSubmit($('chat-form').querySelector('button[type=submit]'));});
$('transfer-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>openConfirmLayer(await api('/transfers/preview',{sourceId:$('source').value,recipientId:$('recipient').value,amountMinor:amount($('amount').value)})));};
$('schedule-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>openConfirmLayer(await api('/schedules/preview',{sourceId:'checking',recipientId:'travel-recipient',amountMinor:amount($('schedule-amount').value),firstDate:$('first-date').value,endDate:$('end-date').value,dayOfMonth:Number($('first-date').value.split('-')[2])})));};
$('simulate-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const results=await api('/simulation/run-due',{date:$('simulation-date').value});results.forEach(showAction);notify(results.length?'已核对 '+results.length+' 个到期操作；请查看回执。':'该日期没有到期任务。');await refresh();});};
const confirmLayer=document.getElementById('confirm-layer');if(confirmLayer){document.getElementById('confirm-layer-close').onclick=closeConfirmLayer;confirmLayer.addEventListener('close',onConfirmLayerClosed);confirmLayer.addEventListener('click',e=>{if(e.target===confirmLayer)closeConfirmLayer();});}
const startupControls=[...document.querySelectorAll('[data-prompt]'),$('open-mandates'),$('chat-form').querySelector('button[type="submit"]')];startupControls.forEach(button=>button.disabled=true);
function wealthAsk(text){if(chatBusy)return {sent:false,reason:'正在处理上一个问题，请等回答出来后再点。'};if(pendingChatRequest)return {sent:false,reason:'上一轮请求尚未核对完成，请先在对话区核对原请求。'};sendQuestion(text);return {sent:true};}
try{if(typeof createWealthPanel==='function'){const wealthHost=document.getElementById('wealth-products');if(wealthHost&&!wealthHost.dataset.mounted){createWealthPanel({node,money,api,askChat:wealthAsk})(wealthHost);wealthHost.dataset.mounted='1';}}}catch(error){console.warn('理财产品面板未初始化',error);}
(async()=>{try{const s=await api('/session');csrf=s.csrf;faultsEnabled=s.faultsEnabled;currentAgentMode=s.mode;message('欢迎进入 SafeBank。账单可以直接提问；查看代扣请先确认查询范围，再分析收费周期。银行操作须核对预览并逐项确认。');await refresh();updateActionHistory();await initializeBusinessContext(s.user);appReady=true;startupControls.forEach(button=>button.disabled=false);setMode(s.mode,s.model);try{await restoreActions();}catch(e){notify('部分任务记录未能恢复：'+e.message);}updateActionHistory();sweepExpiredRecords();setInterval(sweepExpiredRecords,30000);const settings=await loadModelSettings();if(settings&&settings.mode==='mock')message('提示：当前为免费 Mock 模式，不消耗 API 额度。如需真实模型回答，请在左侧「模型设置」填写你自己的 API Key。');else if(!settings)setTimeout(loadModelSettings,6000);}catch(e){notify('初始化失败：'+e.message);$('mode').textContent='连接失败';}})();
// ===== v4 恢复：独立事件绑定 =====
$('workflow-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const mandateIds=[...$('workflow-mandates').querySelectorAll('input:checked')].map(n=>n.value);if(!mandateIds.length)throw Error('请明确选定至少一项月度代扣');await api('/workflows',{month:$('workflow-month').value,mandateIds,sourceId:$('workflow-source').value,recipientId:$('workflow-recipient').value,firstDate:$('workflow-first').value,endDate:$('workflow-end').value,dayOfMonth:Number($('workflow-first').value.split('-')[2]),onlyConfirmedSavings:true});await refresh();});};
$('chat-form').onsubmit=event=>{event.preventDefault();sendQuestion($('prompt').value.trim());};
$('chat-retry').onclick=()=>sendQuestion(lastFailedQuestion);
$('prompt').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing&&event.keyCode!==229){event.preventDefault();$('chat-form').requestSubmit($('chat-form').querySelector('button[type="submit"]'));}});
