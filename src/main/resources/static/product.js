/* Savings, mandate cards and the shared review desk. All writes remain previews. */
(() => {
 let schedules=[],transfers=[],editing=null,reactivating=null,page=1,actionTab='pending';
 const planStates={ACTIVE:'进行中',PAUSED:'已暂停',CANCELLED:'已终止',EXPIRED:'已到期'};
 const today=()=>window.bankBusinessDate||new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date());
 const iso=d=>[d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');
 function due(year,month,day){return new Date(year,month,Math.min(day,new Date(year,month+1,0).getDate()),12);}
 function nextDue(day,from=today()){const [y,m,d]=from.split('-').map(Number);let date=due(y,m-1,day);if(date.getDate()<d)date=due(y,m,day);return iso(date);}
 function dates(first,end,day){$(first).value=nextDue(Number($(day).value));const [y,m]= $(first).value.split('-').map(Number);$(end).value=iso(due(y,m+10,Number($(day).value)));}
 function metric(label,value,note){const el=node('div',undefined,'metric');el.append(node('span',label),node('strong',value));if(note)el.append(node('small',note));return el;}
 function status(text,state){return node('span',text,'state-pill state-'+state.toLowerCase());}
 function button(label,fn,cls){const b=node('button',label,cls);b.type='button';b.onclick=()=>perform(b,fn);return b;}
 function revealAction(id){$('action-search').value='';actionTab=actionCards.get(id)?.parentElement?.id==='history-actions'?'history':'pending';filterActions();$('action-center').scrollIntoView({block:'start',behavior:'smooth'});const card=actionCards.get(id);if(card){card.tabIndex=-1;card.focus({preventScroll:true});}}
 function showPreview(a){showAction(a);revealAction(a.id);}
 function filterActions(){
  const pending=[...$('actions').querySelectorAll('.action')],history=[...$('history-actions').querySelectorAll('.action')];
  $('pending-count').textContent=pending.length;$('receipt-count').textContent=history.length;
  $('actions').hidden=actionTab!=='pending';$('action-history').hidden=actionTab!=='history';$('action-history').open=true;
  document.querySelectorAll('[data-action-tab]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.actionTab===actionTab)));
  const query=$('action-search').value.trim().toLowerCase();let count=0;
  for(const card of [...pending,...history]){card.hidden=!card.textContent.toLowerCase().includes(query);if(!card.hidden&&(actionTab==='pending'?pending:history).includes(card))count++;}
  $('action-filter-empty').hidden=count>0||(!query&&actionTab==='pending'&&!pending.length);
  $('action-filter-empty').textContent=query?'没有匹配的操作。':'暂无历史回执。';
  $('action-center-help').textContent=actionTab==='history'?'查看已完成、失败或取消的原操作回执。':'先核对对象与金额，再勾选确认。处理中或待核实的操作请查询原状态。';
 }
 function renderMandates(rows){
  const root=$('mandates');root.replaceChildren();
  if(rows===null){const empty=node('div',undefined,'empty mandate-empty');empty.append(node('div','◈','empty-symbol'),node('h3','让每一项代扣，都清楚可见'),node('p','确认查询范围后，即可查看服务卡片、收费周期与授权状态。'),button('查看我的代扣',async()=>showPreview(await createMandateQueryIntent()),'primary'));root.append(empty);$('mandate-metrics').replaceChildren();$('workflow-mandates').replaceChildren(node('p','请先在上方查询并确认代扣范围。','hint'));return;}
  const active=rows.filter(r=>r.status==='ACTIVE'),monthlyEstimate=active.reduce((total,r)=>{const months={MONTHLY:1,QUARTERLY:3,YEARLY:12}[r.billing_interval];return months?total+r.amount_minor/months:total;},0);
  $('mandate-metrics').replaceChildren(metric('有效授权',active.length+' 项'),metric('平均每月预计付款',money(monthlyEstimate),'月付按 1 个月、季付按 3 个月、年付按 12 个月折算；未确认周期不计入'),metric('已撤销',rows.length-active.length+' 项'));
  const shown=rows.filter(r=>(!$('mandate-search').value||r.merchant.toLowerCase().includes($('mandate-search').value.trim().toLowerCase()))&&($('mandate-status').value==='all'||r.status===$('mandate-status').value)&&($('mandate-cycle').value==='all'||r.billing_interval===$('mandate-cycle').value));
  shown.forEach((m,i)=>{const card=node('article',undefined,'mandate-card '+(m.status==='ACTIVE'?'':'is-revoked'));const top=node('div',undefined,'service-card-top');top.append(node('span',m.merchant.slice(0,1),'service-icon service-icon-'+i%5),status(m.status==='ACTIVE'?'授权有效':'已撤销',m.status));card.append(top,node('h3',m.merchant));const value=node('p',undefined,'service-price');value.append(node('strong',money(m.amount_minor)),node('span',billingLabel(m.billing_interval)));card.append(value,node('p','下次约定扣费 · '+(m.renewal_date||'未确认'),'hint'));
   const controls=node('div',undefined,'service-card-controls');controls.append(m.status==='ACTIVE'?button('管理 · 撤销授权',async()=>showPreview(await api('/mandates/preview',{mandateId:m.id}))):button('重新开启',async()=>openMandate(m)));card.append(controls);root.append(card);});
  if(!shown.length)root.append(node('p','没有符合筛选条件的代扣服务。','empty mandate-empty'));
  const selected=new Set([...$('workflow-mandates').querySelectorAll('input:checked')].map(n=>n.value));
  $('workflow-mandates').replaceChildren(...rows.map(m=>{const label=node('label',undefined,'workflow-choice');const check=node('input');check.type='checkbox';check.value=m.id;check.disabled=m.status!=='ACTIVE'||m.billing_interval!=='MONTHLY';check.checked=selected.has(m.id)&&!check.disabled;label.append(check,node('span',m.merchant+' · '+money(m.amount_minor)+' · '+billingLabel(m.billing_interval)+(check.disabled?'（不可计入）':'')));return label;}));window.updateMandateSelection?.();
 }
 function openMandate(m){reactivating=m?.id||null;$('mandate-editor').hidden=false;$('mandate-editor-title').textContent=m?'重新开启代扣服务':'开启代扣服务';$('mandate-merchant').value=m?.merchant||'';$('mandate-merchant').readOnly=!!m;$('mandate-amount').value=m?m.amount_minor/100:'';$('mandate-interval').value=m?.billing_interval||'MONTHLY';$('mandate-amount').readOnly=!!m;$('mandate-interval').disabled=!!m;$('mandate-renewal').value=m?.renewal_date>=today()?m.renewal_date:today();$('mandate-editor').scrollIntoView({block:'center'});$('mandate-merchant').focus();}
 function nextPlanDue(s){let date=nextDue(s.day_of_month,s.first_date>today()?s.first_date:today());if(transfers.some(t=>t.scheduleId===s.id&&t.dueDate.slice(0,7)===date.slice(0,7))){const [y,m]=date.split('-').map(Number);date=iso(due(y,m,s.day_of_month));}return date<=s.end_date?date:'已无后续期次';}
 function displayedState(s){return s.status==='ACTIVE'&&s.end_date<today()?'EXPIRED':s.status;}
 function planName(s){return recipientLabel(s.recipient_id)+' · '+s.id.slice(0,8);}
 function renderPlans(){
  const active=schedules.filter(s=>displayedState(s)==='ACTIVE'),successful=transfers.filter(t=>t.status==='VERIFIED_SUCCESS');
  $('savings-metrics').replaceChildren(metric('进行中的计划',active.length+' 项'),metric('每月计划转入',money(active.reduce((n,s)=>n+s.amount_minor,0)),'计划金额，非实际转账'),metric('累计核验转入',money(successful.reduce((n,t)=>n+t.amount_minor,0)),successful.length+' 笔成功转入'));
  const root=$('savings-plans');root.replaceChildren();
  const rows=schedules.filter(s=>$('schedule-filter').value==='all'||displayedState(s)===$('schedule-filter').value);
  rows.forEach(s=>{const state=displayedState(s),card=node('article',undefined,'savings-plan');const top=node('div',undefined,'service-card-top');const calendar=node('div',undefined,'day-badge');calendar.append(node('small','每月'),node('strong',s.day_of_month),node('small','日转入'));top.append(calendar,status(planStates[state]||state,state));card.append(top,node('h3',recipientLabel(s.recipient_id)),node('strong',money(s.amount_minor),'plan-amount'),node('p','来自 '+(accountLabels.get(s.source_id)||s.source_id),'hint'));
   const done=transfers.filter(t=>t.scheduleId===s.id&&t.status==='VERIFIED_SUCCESS');const track=node('div',undefined,'plan-progress');let total=0;for(let d=s.first_date;d<=s.end_date&&total<120;){total++;const [y,m]=d.split('-').map(Number);d=iso(due(y,m,s.day_of_month));}const fill=node('div');fill.style.width=Math.min(100,done.length/Math.max(1,total)*100)+'%';track.append(fill);
   card.append(track,node('p','已核验 '+done.length+' 期 · 累计 '+money(done.reduce((n,t)=>n+t.amount_minor,0)),'plan-progress-note'),node('p',s.first_date+' — '+s.end_date,'hint'));
   if(state==='ACTIVE')card.append(node('p','下次计划日 '+nextPlanDue(s),'plan-next'));
   const controls=node('div',undefined,'plan-controls');if(s.status!=='CANCELLED'){controls.append(button('修改计划',async()=>openSchedule(s)));if(state==='ACTIVE')controls.append(button('暂停',async()=>manage(s,'PAUSE')));if(state==='PAUSED')controls.append(button('恢复',async()=>manage(s,'RESUME')));controls.append(button('终止',async()=>manage(s,'CANCEL'),'danger-text'));}controls.append(button('转账明细',async()=>{$('savings-plan-filter').value=s.id;page=1;renderTransfers();$('savings-history-panel').scrollIntoView({block:'start'});}));card.append(controls);root.append(card);});
  if(!rows.length){const empty=node('div',undefined,'empty mandate-empty');empty.append(node('h3',schedules.length?'没有符合条件的计划':'从第一笔储蓄开始'),node('p','计划确认登记后，会在这里显示进度并支持持续管理。'));root.append(empty);}
 }
 async function manage(s,operation){showPreview(await api('/schedules/'+encodeURIComponent(s.id)+'/preview',{operation}));}
 function openSchedule(s){editing=s?.id||null;$('schedule-editor').hidden=false;$('schedule-editor-title').textContent=s?'修改储蓄计划':'新建定时储蓄';$('schedule-editor-eyebrow').textContent=s?'UPDATE SAVINGS PLAN':'NEW SAVINGS PLAN';$('schedule-save').textContent=s?'生成修改预览':'生成计划预览';if(s){$('schedule-source').value=s.source_id;$('schedule-source').dispatchEvent(new Event('change'));$('schedule-recipient').value=s.recipient_id;$('schedule-amount').value=s.amount_minor/100;$('schedule-day').value=s.day_of_month;$('first-date').value=s.first_date;$('end-date').value=s.end_date;}else{dates('first-date','end-date','schedule-day');}rule();$('schedule-editor').scrollIntoView({block:'center'});$('schedule-amount').focus();}
 function rule(){$('schedule-rule').textContent='每月 '+$('schedule-day').value+' 日转入 · 首次 '+$('first-date').value+' · 遇短月按月末执行。'+(editing?' 修改仅影响后续执行，已受理或已完成的转账保留原记录。':'');}
 function renderTransfers(){
  const query=$('savings-search').value.trim().toLowerCase(),from=$('savings-from').value,to=$('savings-to').value;
  let rows=transfers.filter(t=>{const s=schedules.find(s=>s.id===t.scheduleId),needle=[recipientLabel(t.recipient_id),accountLabels.get(t.source_id),t.id,t.scheduleId,s&&planName(s)].join(' ').toLowerCase();return (!query||needle.includes(query))&&($('savings-plan-filter').value==='all'||t.scheduleId===$('savings-plan-filter').value)&&(!from||t.dueDate>=from)&&(!to||t.dueDate<=to)&&($('savings-status').value==='all'||($('savings-status').value==='processing'?['AUTHORIZED','PENDING','UNKNOWN'].includes(t.status):t.status===$('savings-status').value));}).sort((a,b)=>b.dueDate.localeCompare(a.dueDate));
  $('savings-history-summary').textContent=from&&to&&from>to?'开始日期不能晚于结束日期。':'共 '+rows.length+' 笔 · 已核验转入 '+money(rows.filter(t=>t.status==='VERIFIED_SUCCESS').reduce((n,t)=>n+t.amount_minor,0))+' · 金额按每次执行时的原始参数展示';
  const pages=Math.max(1,Math.ceil(rows.length/8));page=Math.min(page,pages);const body=$('savings-transfers');body.replaceChildren();rows.slice((page-1)*8,page*8).forEach(t=>{const row=node('tr');row.append(node('td',t.dueDate));const target=node('td');target.append(node('strong',recipientLabel(t.recipient_id)),node('small',(accountLabels.get(t.source_id)||t.source_id)+' · '+t.scheduleId.slice(0,8)));const value=node('td',money(t.amount_minor),'numeric');const result=node('td');result.append(status(t.status==='VERIFIED_SUCCESS'?'转入成功':statusLabel[t.status]||t.status,t.status));const control=node('td');control.append(button('查看',async()=>showPreview(await api('/actions/'+encodeURIComponent(t.id)))));row.append(target,value,result,control);body.append(row);});
  if(!rows.length){const tr=node('tr'),td=node('td','暂无转账记录。登记计划后，执行产生的记录会显示在这里。','empty');td.colSpan=5;tr.append(td);body.append(tr);}$('savings-page-info').textContent='第 '+page+' / '+pages+' 页';$('savings-page-prev').disabled=page<=1;$('savings-page-next').disabled=page>=pages;
 }
 async function refreshSavings(rows){const records=await api('/schedules/transfers');schedules=rows;transfers=records;const value=$('savings-plan-filter').value;const all=node('option','全部计划');all.value='all';$('savings-plan-filter').replaceChildren(all);for(const s of schedules){const option=node('option',planName(s));option.value=s.id;$('savings-plan-filter').append(option);}if([...$('savings-plan-filter').options].some(o=>o.value===value))$('savings-plan-filter').value=value;renderPlans();renderTransfers();}
 function init(){
  for(const id of ['schedule-day','workflow-day']){for(let i=1;i<=31;i++){const option=node('option',i+' 日'+(i>28?' · 短月按月末':''));option.value=i;$(id).append(option);}$(id).value=20;}
  dates('first-date','end-date','schedule-day');dates('workflow-first','workflow-end','workflow-day');rule();
  for(const id of ['first-date','workflow-first','mandate-renewal'])$(id).min=today();
  $('mandate-renewal').value=today();$('simulation-date').value=$('first-date').value;
  $('schedule-day').onchange=()=>{dates('first-date','end-date','schedule-day');rule();};$('workflow-day').onchange=()=>dates('workflow-first','workflow-end','workflow-day');$('first-date').onchange=rule;
  $('schedule-create').onclick=()=>openSchedule(null);$('schedule-editor-close').onclick=()=>$('schedule-editor').hidden=true;
  $('schedule-filter').onchange=renderPlans;
  $('schedule-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const values={sourceId:$('schedule-source').value,recipientId:$('schedule-recipient').value,amountMinor:amount($('schedule-amount').value),firstDate:$('first-date').value,endDate:$('end-date').value,dayOfMonth:Number($('schedule-day').value)};showPreview(await api(editing?'/schedules/'+editing+'/preview':'/schedules/preview',editing?{operation:'EDIT',...values}:values));});};
  $('mandate-add').onclick=()=>openMandate(null);$('mandate-editor-close').onclick=()=>$('mandate-editor').hidden=true;
  $('mandate-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>showPreview(await api('/mandates/activate/preview',{mandateId:reactivating,merchant:$('mandate-merchant').value,amountMinor:amount($('mandate-amount').value),billingInterval:$('mandate-interval').value,renewalDate:$('mandate-renewal').value})));};
  for(const id of ['mandate-search','mandate-status','mandate-cycle'])$(id).addEventListener(id==='mandate-search'?'input':'change',()=>renderMandates(confirmedMandates));
  $('mandate-refresh').onclick=()=>perform($('mandate-refresh'),async()=>showPreview(await createMandateQueryIntent()));
  for(const id of ['savings-search','savings-status','savings-plan-filter','savings-from','savings-to'])$(id).addEventListener(id==='savings-search'?'input':'change',()=>{page=1;renderTransfers();});
  $('savings-filters').onsubmit=e=>e.preventDefault();$('savings-filters').onreset=()=>setTimeout(()=>{page=1;renderTransfers();},0);
  $('savings-page-prev').onclick=()=>{page--;renderTransfers();};$('savings-page-next').onclick=()=>{page++;renderTransfers();};$('savings-reload').onclick=()=>perform($('savings-reload'),refresh);
  document.querySelectorAll('[data-action-tab]').forEach(b=>b.onclick=()=>{actionTab=b.dataset.actionTab;filterActions();});$('action-search').oninput=filterActions;filterActions();
 }
 window.bankUI={init,refreshSavings,renderMandates,revealAction,filterActions};
})();
