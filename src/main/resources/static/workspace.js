/* Product workspace: shared people, monthly statements and synthetic card management. */
(() => {
 'use strict';
 let accounts=[],people=[],cards=[],operations=[],months=[],statement=null,editingUser=null;
 let statementRequest=0,page=1,loaded=false,profileLoaded=false;
 const pageSize=8;
 const genderLabel={UNKNOWN:'未填写',MALE:'男',FEMALE:'女',UNDISCLOSED:'不愿透露'};
 const cardState={ACTIVE:'使用中',LOCKED:'已临时锁定',LOST:'已挂失'};
 const cardKind={APPLY:'申请卡片',UPDATE_LIMITS:'调整交易限额',UPDATE_CONTROLS:'设置交易开关',LOCK:'临时锁卡',UNLOCK:'解除临时锁定',REPORT_LOST:'挂失卡片'};
 const optionalMinor=value=>{
  if(value.trim()==='')return null;
  if(!/^\d+(\.\d{1,2})?$/.test(value))throw Error('金额须为非负数，最多两位小数');
  const [whole,fraction='']=value.split('.'),n=Number(whole)*100+Number(fraction.padEnd(2,'0'));
  if(!Number.isSafeInteger(n))throw Error('金额超出可用范围');return n;
 };
 function options(select,rows,empty='暂无可选项'){
  const previous=select.value;select.replaceChildren();
  for(const [value,label] of rows){const o=node('option',label);o.value=value;select.append(o);}
  if(!rows.length){const o=node('option',empty);o.value='';select.append(o);}
  if(rows.some(r=>r[0]===previous))select.value=previous;
  select.disabled=!rows.length;
 }
 function personLabel(p){return p.name+' · '+(p.phone?'尾号'+p.phone.slice(-4)+' · ':'')+(p.note||p.accountLabel);}
 window.workspaceRecipientLabel=id=>{const p=people.find(p=>p.id===id);return p?p.name+' / '+p.accountLabel:id;};
 function transactionMerchant(t){
  if(!t.merchant?.startsWith('转账 / '))return t.merchant;
  const id=t.merchant.slice('转账 / '.length),label=window.workspaceRecipientLabel(id);
  return '转账 / '+(label!==id?label:accounts.find(a=>a.id===id)?.label||id);
 }
 function metric(label,value,cls=''){
  const box=node('div',undefined,'metric '+cls);box.append(node('span',label),node('strong',value));return box;
 }
 function setFormAvailable(id,available){$(id).querySelector('button[type="submit"],button:not([type])').disabled=!available;}
 window.workspaceSavingsOptions=()=>people.filter(p=>p.savingsTarget).map(p=>[p.id,personLabel(p)]);
 function fillRecipients(){
  const text=$('recipient-search').value.trim().toLowerCase();
  const rows=people.filter(p=>p.accountId!==$('source').value&&(!text||[p.name,p.phone,p.note,p.accountLabel].join(' ').toLowerCase().includes(text)));
  options($('recipient'),rows.map(p=>[p.id,personLabel(p)]),'没有匹配收款人，请在用户管理中添加');
  setFormAvailable('transfer-form',!!rows.length&&!!$('source').value);
  for(const prefix of ['schedule','workflow']){
   const targets=people.filter(p=>p.savingsTarget&&p.accountId!==$(prefix+'-source').value);
   options($(prefix+'-recipient'),targets.map(p=>[p.id,personLabel(p)]),'暂无储蓄目标，请到用户管理添加');
   setFormAvailable(prefix==='schedule'?'schedule-form':'workflow-form',!!targets.length&&!!$(prefix+'-source').value);
  }
 }
 window.refreshWorkspace=async currentAccounts=>{
  const balancesChanged=JSON.stringify(accounts.map(a=>[a.id,a.balance_minor]))!==JSON.stringify(currentAccounts.map(a=>[a.id,a.balance_minor]));
  accounts=currentAccounts;
  const [contacts,cardList,history]=await Promise.all([api('/users'),api('/cards'),api('/cards/operations')]);
  people=contacts;cards=cardList;operations=history;
  const accountRows=accounts.map(a=>[a.id,a.label+' · '+money(a.balance_minor)]);
  for(const id of ['source','schedule-source','workflow-source','card-account'])options($(id),accountRows);
  fillRecipients();renderPeople();renderCards();renderCardHistory();
  if(!profileLoaded){const profile=await api('/users/profile');$('profile-name').value=profile.name;$('profile-phone').value=profile.phone;$('profile-gender').value=profile.gender;profileLoaded=true;}
  if(!loaded||balancesChanged){await loadMonths(loaded?$('statement-month').value:undefined);loaded=true;}
 };
 async function loadMonths(preferred){
  months=await api('/statement/months');
  options($('statement-month'),months.map(m=>[m,m.replace('-',' 年 ')+' 月']),'暂无历史账单');
  if(preferred&&!months.includes(preferred)){months.unshift(preferred);options($('statement-month'),months.map(m=>[m,m]));}
  if(preferred)$('statement-month').value=preferred;
  if(months.length)await loadStatement();else{$('statement-status').textContent='暂无账单记录。';renderTransactions([]);}
 }
 async function loadStatement(){
  const selected=$('statement-month').value;if(!selected)return;
  const request=++statementRequest;
  $('statement-status').textContent='正在读取 '+selected+' 账单…';$('transaction-panel').setAttribute('aria-busy','true');
  try{
   const data=await api('/statement?month='+encodeURIComponent(selected));
   if(request!==statementRequest)return;
   statement=data;page=1;$('transaction-filters').reset();
   const categories=[...new Set(data.transactions.map(t=>t.category))].sort();
   options($('tx-category'),[['','全部类别'],...categories.map(c=>[c,c])]);$('tx-category').value='';
   const end=new Date(Number(selected.slice(0,4)),Number(selected.slice(5)),0).getDate();
   for(const id of ['tx-from','tx-to']){$(id).min=selected+'-01';$(id).max=selected+'-'+end;}
   $('statement-status').textContent=selected+' · '+data.transactions.length+' 笔交易 · 账户内部转账已单独识别';
   $('statement-metrics').replaceChildren(metric('本月支出',money(data.expense_minor),'expense'),metric('本月收入',money(data.income_minor),'income'),metric('净收支',money(data.income_minor-data.expense_minor)),metric('交易笔数',String(data.transactions.length)+' 笔'));
   renderCategories(data);filterTransactions();
   $('month-prev').disabled=months.indexOf(selected)>=months.length-1;
   $('month-next').disabled=months.indexOf(selected)<=0;
   await renderTrend(request);
  }catch(error){if(request!==statementRequest)return;statement=null;$('statement-status').textContent='账单读取失败：'+error.message;$('statement-metrics').replaceChildren();$('statement-categories').replaceChildren();$('statement-trend').replaceChildren();renderTransactions([]);}
  finally{if(request===statementRequest)$('transaction-panel').setAttribute('aria-busy','false');}
 }
 function renderCategories(data){
  const entries=Object.entries(data.categories).sort((a,b)=>b[1]-a[1]);
  $('statement-categories').replaceChildren(...entries.map(([category,value],index)=>{
   const row=node('button',undefined,'spend-category');row.type='button';row.title='筛选 '+category+' 的交易';
   const top=node('span',undefined,'spend-category-top');top.append(node('span',category),node('strong',money(value)));
   const track=node('span',undefined,'spend-track'),bar=node('span',undefined,'spend-bar');
   bar.style.width=(value/data.expense_minor*100)+'%';bar.style.background=['#4fc3ed','#6d8aff','#45c6b0','#e6b872','#a699ef','#8eafc6'][index%6];
   track.append(bar);row.append(top,track,node('small',(value/data.expense_minor*100).toFixed(1)+'%'));
   row.onclick=()=>{$('tx-category').value=category;page=1;filterTransactions();$('transaction-panel').scrollIntoView({block:'start',behavior:'smooth'});};return row;
  }));
  if(!entries.length)$('statement-categories').append(node('p','本月暂无支出记录。','empty'));
 }
 async function renderTrend(request){
  const periods=months.slice(0,6).reverse();
  const data=await Promise.all(periods.map(m=>m===statement?.month?statement:api('/statement?month='+m)));
  if(request!==statementRequest)return;
  const max=Math.max(1,...data.flatMap(d=>[d.income_minor,d.expense_minor]));
  $('statement-trend').replaceChildren(...data.map(d=>{
   const item=node('button',undefined,'trend-month'+(d.month===statement.month?' selected':''));item.type='button';
   item.setAttribute('aria-label',d.month+' 收入 '+money(d.income_minor)+'，支出 '+money(d.expense_minor));item.title=d.month+'\n收入 '+money(d.income_minor)+'\n支出 '+money(d.expense_minor);
   const bars=node('span',undefined,'trend-bars');for(const [key,cls] of [['income_minor','trend-income'],['expense_minor','trend-expense']]){const bar=node('span',undefined,cls);bar.style.height=Math.max(d[key]>0?2:0,d[key]/max*100)+'%';bars.append(bar);}
   item.append(bars,node('span',d.month.slice(5)+'月'));item.onclick=()=>window.openStatementMonth(d.month);return item;
  }));
 }
 window.openStatementMonth=async month=>{selectView('statement');if(!months.includes(month))await loadMonths(month);else{$('statement-month').value=month;await loadStatement();}$('transaction-panel').scrollIntoView({block:'start',behavior:'smooth'});};
 function filterTransactions(){
  if(!statement)return;
  try{
   const min=optionalMinor($('tx-min').value),max=optionalMinor($('tx-max').value),from=$('tx-from').value,to=$('tx-to').value;
   if(from&&to&&from>to)throw Error('开始日期不能晚于结束日期');
   if(min!==null&&max!==null&&min>max)throw Error('最低金额不能大于最高金额');
   const search=$('tx-search').value.trim().toLowerCase(),category=$('tx-category').value,direction=$('tx-direction').value;
   let rows=statement.transactions.filter(t=>{
    const internal=t.category==='内部转账',n=Math.abs(t.amount_minor);
    return (!search||[t.merchant,transactionMerchant(t),t.category,t.id].join(' ').toLowerCase().includes(search))&&(!category||t.category===category)&&(!from||t.booked_on>=from)&&(!to||t.booked_on<=to)&&(min===null||n>=min)&&(max===null||n<=max)&&(!direction||(direction==='internal'?internal:!internal&&(direction==='expense'?t.amount_minor<0:t.amount_minor>0)));
   });
   const sort=$('tx-sort').value;
   rows.sort((a,b)=>{const order=sort.startsWith('amount')?Math.abs(a.amount_minor)-Math.abs(b.amount_minor):a.booked_on.localeCompare(b.booked_on);return (sort.endsWith('desc')?-order:order)||a.id.localeCompare(b.id);});
   $('tx-error').hidden=true;renderTransactions(rows);
  }catch(error){$('tx-error').textContent=error.message;$('tx-error').hidden=false;}
 }
 function renderTransactions(rows){
  const totalPages=Math.max(1,Math.ceil(rows.length/pageSize));page=Math.min(page,totalPages);
  const incoming=rows.filter(t=>t.category!=='内部转账'&&t.amount_minor>0).reduce((a,t)=>a+t.amount_minor,0);
  const outgoing=rows.filter(t=>t.category!=='内部转账'&&t.amount_minor<0).reduce((a,t)=>a-t.amount_minor,0);
  $('transaction-count').textContent=statement?.month?statement.month+' · 全月 '+statement.transactions.length+' 笔':'';
  $('tx-filter-summary').textContent='筛选结果 '+rows.length+' 笔 · 收入 '+money(incoming)+' · 支出 '+money(outgoing)+'（金额区间按绝对值筛选）';
  $('transaction-rows').replaceChildren(...rows.slice((page-1)*pageSize,page*pageSize).map(t=>{
   const tr=node('tr');tr.dataset.transactionId=t.id;
   const label=transactionMerchant(t),merchant=node('td'),wrap=node('div',undefined,'merchant-cell'),icon=node('span',(label||'账').slice(0,1),'merchant-icon'),name=node('div');
   name.append(node('strong',label),node('small',t.id));wrap.append(icon,name);merchant.append(wrap);
   const date=node('td',t.booked_on),category=node('td');category.append(node('span',t.category,'tag'));
   const value=node('td',(t.amount_minor>0?'+':'−')+money(Math.abs(t.amount_minor)),'numeric amount '+(t.category==='内部转账'?'internal':t.amount_minor>0?'income':'expense'));
   tr.append(merchant,date,category,value);return tr;
  }));
  if(!rows.length){const tr=node('tr'),td=node('td','没有符合条件的交易。可以调整筛选条件或切换月份。','empty');td.colSpan=4;tr.append(td);$('transaction-rows').append(tr);}
  $('tx-page-info').textContent='第 '+page+' / '+totalPages+' 页 · 每页 '+pageSize+' 笔';
  $('tx-prev').disabled=page<=1;$('tx-next').disabled=page>=totalPages;
 }
 $('statement-month').onchange=loadStatement;
 $('month-prev').onclick=()=>{const i=months.indexOf($('statement-month').value);if(i<months.length-1){$('statement-month').value=months[i+1];loadStatement();}};
 $('month-next').onclick=()=>{const i=months.indexOf($('statement-month').value);if(i>0){$('statement-month').value=months[i-1];loadStatement();}};
 $('statement-reload').onclick=e=>perform(e.currentTarget,()=>loadMonths($('statement-month').value));
 $('transaction-filters').onsubmit=e=>{e.preventDefault();page=1;filterTransactions();};
 for(const id of ['tx-sort','tx-category','tx-direction'])$(id).onchange=()=>{page=1;filterTransactions();};
 $('tx-search').oninput=()=>{page=1;filterTransactions();};
 $('tx-reset').onclick=()=>{$('transaction-filters').reset();$('tx-category').value='';page=1;filterTransactions();};
 $('tx-prev').onclick=()=>{page--;filterTransactions();};$('tx-next').onclick=()=>{page++;filterTransactions();};
 for(const id of ['source','schedule-source','workflow-source'])$(id).onchange=fillRecipients;
 $('recipient-search').oninput=fillRecipients;
 document.querySelectorAll('[data-open-users]').forEach(b=>b.onclick=()=>selectView('users'));
 window.updateMandateSelection=()=>{
  const selected=new Set([...$('workflow-mandates').querySelectorAll('input:checked')].map(c=>c.value));
  const sum=(confirmedMandates||[]).filter(m=>selected.has(m.id)).reduce((n,m)=>n+m.amount_minor,0);
  $('mandate-selection-summary').textContent=confirmedMandates===null?'查询并确认后，将显示可勾选的代扣项目。':'已选择 '+selected.size+' 项 · 预计每月节省 '+money(sum)+'。创建任务后仍须逐项确认撤销。';
 };
 $('workflow-mandates').addEventListener('change',window.updateMandateSelection);window.updateMandateSelection();
 function renderPeople(){
  $('user-metrics').replaceChildren(metric('本人账户余额',money(accounts.reduce((s,a)=>s+a.balance_minor,0))),metric('已维护联系人',people.length+' 位'),metric('可用储蓄目标',people.filter(p=>p.savingsTarget).length+' 个'));
  const text=$('user-search').value.trim().toLowerCase(),matches=people.filter(p=>[p.name,p.phone,p.note,p.accountLabel].join(' ').toLowerCase().includes(text));
  $('managed-users').replaceChildren(...matches.map(p=>{
   const card=node('article',undefined,'person-card');card.dataset.personId=p.id;
   const head=node('div',undefined,'person-heading'),avatar=node('span',p.name.slice(0,1),'person-avatar'),identity=node('div');
   identity.append(node('h3',p.name),node('p',(p.phone||'未填写手机号')+' · '+genderLabel[p.gender],'hint'));head.append(avatar,identity);card.append(head);
   const tags=node('div',undefined,'person-tags');tags.append(node('span',p.ownAccount?'本人账户':'合成收款账户','tag'));if(p.savingsTarget)tags.append(node('span','储蓄目标','tag'));card.append(tags);
   card.append(node('p',p.note||'暂无备注','hint'),node('span',p.accountLabel,'account-caption'),node('strong',money(p.balanceMinor),'person-balance'));
   const edit=node('button','编辑资料');edit.type='button';edit.onclick=()=>openUser(p);card.append(edit);return card;
  }));
  if(!matches.length)$('managed-users').append(node('p','没有匹配的用户。添加用户后，可直接在转账与储蓄中选择。','empty'));
 }
 function userAccountInfo(){const isNew=$('user-account').value==='';$('user-balance-label').hidden=!!editingUser||!isNew;$('user-linked-info').textContent=editingUser?'关联账户已固定；余额随演示账务变化，编辑资料不会改变余额。':isNew?'将创建独立的合成收款账户，初始余额仅用于演示。':'关联本人已有账户，不会新增余额。';}
 function openUser(person=null){
  editingUser=person;$('user-form').reset();$('user-editor-title').textContent=person?'编辑用户资料':'添加用户';
  $('user-name').value=person?.name||'';$('user-phone').value=person?.phone||'';$('user-gender').value=person?.gender||'UNKNOWN';$('user-note').value=person?.note||'';$('user-savings').checked=person?.savingsTarget||false;
  options($('user-account'),person?[[person.accountId,person.accountLabel]]:[['','新建合成收款账户'],...accounts.map(a=>[a.id,a.label+' · 本人账户'])]);
  $('user-account').disabled=!!person;$('user-balance').value='0';userAccountInfo();$('user-editor').hidden=false;$('user-editor').scrollIntoView({block:'start',behavior:'smooth'});$('user-name').focus({preventScroll:true});
 }
 $('user-add').onclick=()=>openUser();$('user-editor-close').onclick=()=>{$('user-editor').hidden=true;editingUser=null;};$('user-account').onchange=userAccountInfo;$('user-search').oninput=renderPeople;
 $('profile-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{await api('/users/profile',{name:$('profile-name').value.trim(),phone:$('profile-phone').value.trim(),gender:$('profile-gender').value});$('profile-details').open=false;notify('我的资料已保存');});};
 $('user-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{
  const request={name:$('user-name').value.trim(),phone:$('user-phone').value.trim(),gender:$('user-gender').value,note:$('user-note').value.trim(),savingsTarget:$('user-savings').checked,accountId:$('user-account').value||null,initialBalanceMinor:editingUser||$('user-account').value?0:(optionalMinor($('user-balance').value)||0)};
  await api(editingUser?'/users/'+encodeURIComponent(editingUser.id):'/users',request);$('user-editor').hidden=true;editingUser=null;await refresh();notify('资料已保存，转账收款人与储蓄目标已同步');
 });};
 function renderCards(){
  $('bank-cards').replaceChildren(...cards.map((c,index)=>{
   const card=node('article',undefined,'bank-card '+(c.status==='ACTIVE'?'':c.status.toLowerCase()));card.dataset.cardId=c.id;card.dataset.cardVersion=String(c.version);
   const top=node('div',undefined,'bank-card-top');top.append(node('span','SafeBank'),node('span',cardState[c.status],'card-status'));card.append(top,node('h3',c.label),node('span',c.maskedNumber,'card-number'),node('p',(c.cardType==='VIRTUAL'?'虚拟卡':'储蓄卡')+' · '+c.accountLabel,'card-account'));
   const balance=node('div',undefined,'card-balance');balance.append(node('span','关联账户可用余额'),node('strong',money(c.balanceMinor)));card.append(balance);
   card.append(node('p','单笔 '+money(c.perTransactionLimitMinor)+' / 每日 '+money(c.dailyLimitMinor),'card-limits'),node('p','线上 '+(c.onlineEnabled?'已开启':'已关闭')+' · 境外 '+(c.overseasEnabled?'已开启':'已关闭'),'card-limits'));
   if(c.status!=='LOST'){const controls=node('div',undefined,'card-actions');for(const [kind,label] of [['UPDATE_LIMITS','设置'],[c.status==='LOCKED'?'UNLOCK':'LOCK',c.status==='LOCKED'?'解锁':'锁卡'],['REPORT_LOST','挂失']]){const b=node('button',label);b.type='button';b.onclick=()=>openCardSettings(c.id,kind);controls.append(b);}card.append(controls);}else card.append(node('p','挂失不可恢复，可关联原账户申请新卡。','card-limits'));
   return card;
  }));
  if(!cards.length)$('bank-cards').append(node('p','还没有卡片。申请演示卡，体验关联账户、调整限额与锁卡流程。','empty'));
  const usable=cards.filter(c=>c.status!=='LOST');options($('card-target'),usable.map(c=>[c.id,c.label+' · '+c.maskedNumber]));
  if(!usable.length)$('card-settings-panel').hidden=true;
  else if(!$('card-settings-panel').hidden)populateCardSettings();
 }
 function openCardSettings(id,kind){$('card-settings-panel').hidden=false;$('card-target').value=id;$('card-kind').value=kind;populateCardSettings();$('card-settings-panel').scrollIntoView({block:'start',behavior:'smooth'});}
 function populateCardSettings(){const c=cards.find(c=>c.id===$('card-target').value);if(!c)return;for(const o of $('card-kind').options)o.disabled=(o.value==='LOCK'&&c.status!=='ACTIVE')||(o.value==='UNLOCK'&&c.status!=='LOCKED');if($('card-kind').selectedOptions[0].disabled)$('card-kind').value='UPDATE_LIMITS';$('card-per').value=(c.perTransactionLimitMinor/100).toFixed(2);$('card-daily').value=(c.dailyLimitMinor/100).toFixed(2);$('card-online').checked=c.onlineEnabled;$('card-overseas').checked=c.overseasEnabled;updateCardFields();}
 function updateCardFields(){const kind=$('card-kind').value,limits=kind==='UPDATE_LIMITS';$('card-per-label').hidden=$('card-daily-label').hidden=!limits;$('card-per').required=$('card-daily').required=limits;$('card-per').disabled=$('card-daily').disabled=!limits;$('card-control-fields').hidden=kind!=='UPDATE_CONTROLS';$('card-settings-help').textContent={UPDATE_LIMITS:'单笔限额不可超过每日限额，每日最高 ¥100,000。演示系统保存设置，未接入真实消费网络。',UPDATE_CONTROLS:'开关只影响演示卡片设置，修改后可在总览查看。',LOCK:'临时锁定后，可通过解锁恢复卡片。关联账户仍可用于转账与储蓄。',UNLOCK:'仅已临时锁定的卡片可以解锁。',REPORT_LOST:'挂失后这张卡片将永久停用，不能直接解挂；需要使用时请重新申请卡片。'}[kind];}
 $('card-apply-open').onclick=()=>{$('card-apply-panel').hidden=false;$('card-apply-panel').scrollIntoView({block:'start',behavior:'smooth'});$('card-label').focus({preventScroll:true});};$('card-apply-close').onclick=()=>{$('card-apply-panel').hidden=true;};
 $('card-target').onchange=populateCardSettings;$('card-kind').onchange=updateCardFields;
 $('card-apply-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const p=await api('/cards/preview',{kind:'APPLY',accountId:$('card-account').value,label:$('card-label').value.trim(),cardType:$('card-type').value,perTransactionLimitMinor:amount($('card-apply-per').value),dailyLimitMinor:amount($('card-apply-daily').value),onlineEnabled:$('card-apply-online').checked,overseasEnabled:$('card-apply-overseas').checked});showCardOperation(p);await refresh();});};
 $('card-settings-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,async()=>{const kind=$('card-kind').value,request={kind,cardId:$('card-target').value};if(kind==='UPDATE_LIMITS'){request.perTransactionLimitMinor=amount($('card-per').value);request.dailyLimitMinor=amount($('card-daily').value);}if(kind==='UPDATE_CONTROLS'){request.onlineEnabled=$('card-online').checked;request.overseasEnabled=$('card-overseas').checked;}showCardOperation(await api('/cards/preview',request));await refresh();});};
 function showCardOperation(operation,scroll=true){
  const r=operation.request,box=$('card-preview');box.replaceChildren();$('card-preview-panel').hidden=false;
  box.append(node('h3',cardKind[operation.kind]),node('p',operation.status==='COMPLETED'?'已确认并完成 · '+new Date(operation.executedAt).toLocaleString('zh-CN'):operation.status==='CANCELLED'?'预览已取消，未执行本次操作。':'待明确确认 · 预览有效至 '+new Date(operation.expiresAt).toLocaleTimeString('zh-CN'),'hint'));
  const dl=node('dl',undefined,'preview-facts');
  const current=cards.find(c=>c.id===r.cardId);
  const facts=[['卡片',r.label||current?.label||r.cardId],['关联账户',accounts.find(a=>a.id===r.accountId)?.label||current?.accountLabel]];
  if(r.cardType)facts.push(['卡片类型',r.cardType==='VIRTUAL'?'虚拟卡 · 演示':'储蓄卡 · 演示']);
  if(r.perTransactionLimitMinor!=null)facts.push(['单笔限额',money(r.perTransactionLimitMinor)],['每日限额',money(r.dailyLimitMinor)]);
  if(r.onlineEnabled!=null)facts.push(['线上交易',r.onlineEnabled?'允许':'关闭'],['境外交易',r.overseasEnabled?'允许':'关闭']);
  if(operation.kind==='REPORT_LOST')facts.push(['操作影响','永久停用此卡，无法解挂；关联账户余额保留']);
  for(const [label,value] of facts)if(value)dl.append(node('dt',label),node('dd',value));box.append(dl);
  if(operation.card)box.append(node('p',operation.card.maskedNumber+' · '+cardState[operation.card.status]+' · 余额 '+money(operation.card.balanceMinor),'receipt-success'));
  if(operation.receiptId)box.append(node('p','回执编号：'+operation.receiptId,'hint'));
  const error=node('p',undefined,'result-error');error.setAttribute('role','alert');box.append(error);
  const controls=node('div',undefined,'controls');
  if(operation.status==='AWAITING_CONFIRMATION'){
   const expired=new Date(operation.expiresAt)<=new Date();
   if(expired)box.append(node('p','预览已过期，请根据当前卡片状态重新生成。','result-error'));
   else{
    const label=node('label',undefined,'check-line'),check=node('input');check.type='checkbox';check.id='card-explicit-confirm';label.append(check,node('span','我已核对上述信息，并明确确认本次卡片操作'));box.append(label);
    const confirm=node('button','确认并执行','primary');confirm.type='button';confirm.id='card-confirm-button';confirm.disabled=true;check.onchange=()=>confirm.disabled=!check.checked;
    confirm.onclick=()=>perform(confirm,async()=>{try{const result=await api('/cards/operations/'+operation.id+'/confirm',{payloadHash:operation.payloadHash,confirmed:check.checked});showCardOperation(result,false);$('card-apply-panel').hidden=true;await refresh();notify(cardKind[operation.kind]+'已完成');}catch(e){error.textContent=e.message+'。可查询原操作状态，重试沿用当前操作编号。';}});controls.append(confirm);
   }
   const cancel=node('button','取消预览');cancel.type='button';cancel.onclick=()=>perform(cancel,async()=>{showCardOperation(await api('/cards/operations/'+operation.id+'/cancel',{}),false);await refresh();});controls.append(cancel);
  }
  const query=node('button','查询原操作状态');query.type='button';query.onclick=()=>perform(query,async()=>{showCardOperation(await api('/cards/operations/'+operation.id),false);await refresh();});controls.append(query);box.append(controls);
  if(scroll)$('card-preview-panel').scrollIntoView({block:'center',behavior:'smooth'});
 }
 function renderCardHistory(){
  $('card-history').replaceChildren(...operations.map(o=>{
   const item=node('div',undefined,'card-history-item'),description=node('div');description.append(node('strong',cardKind[o.kind]),node('small',new Date(o.createdAt).toLocaleString('zh-CN')+' · '+({COMPLETED:'已完成',CANCELLED:'已取消',AWAITING_CONFIRMATION:new Date(o.expiresAt)<new Date()?'已过期':'待确认'}[o.status]),'hint'));
   const inspect=node('button',o.status==='AWAITING_CONFIRMATION'?'查看预览':'查看回执');inspect.type='button';inspect.onclick=()=>perform(inspect,async()=>showCardOperation(await api('/cards/operations/'+o.id)));item.append(description,inspect);return item;
  }));if(!operations.length)$('card-history').append(node('p','暂无卡片操作记录。','empty'));
 }
})();
