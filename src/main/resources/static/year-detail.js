/* 年度卡片里的月份入口与当月明细。
   只调用受会话鉴权的只读月报接口，不发起对话、不调用模型；所有文本用 textContent 渲染。 */
function monthDetailModel(vo,month,money){
 const rows=(vo&&Array.isArray(vo.transactions)?vo.transactions:[]).map(row=>{
  const amount=Number(row.amount_minor||0);
  const internal=row.category==='内部转账';
  return {booked:String(row.booked_on||''),merchant:String(row.merchant||''),category:String(row.category||''),
   direction:internal?'内部转账':amount<0?'支出':amount>0?'收入':'无金额变动',
   amount:money(Math.abs(amount))};
 });
 return {month,empty:rows.length===0,rows,
  expense:money(vo&&vo.expense_minor||0),income:money(vo&&vo.income_minor||0),
  internal:money(vo&&vo.internal_transfer_minor||0),
  /* 服务端返回错误码时不能当空月报渲染 */
  error:vo&&typeof vo.code==='string'&&vo.code?vo.code:null};
}
function createYearMonths(deps){
 const {node,money,api}=deps;
 return function mount(host,data){
  const state={seq:0,collapsed:true,month:null};
  const list=node('div',undefined,'year-month-buttons');
  const detail=node('section',undefined,'year-detail');detail.hidden=true;
  const months=Array.isArray(data&&data.months)?data.months:[];
  for(const row of months){
   const button=node('button',row.month+' · 支出 '+money(row.expense_minor)+' · 收入 '+money(row.income_minor)+' · '+row.transaction_count+' 笔','year-month');
   button.type='button';button.dataset.month=row.month;
   button.onclick=()=>open(row.month);
   list.append(button);
  }
  host.append(list,detail);
  function open(month){
   const mine=++state.seq;state.collapsed=false;state.month=month;
   detail.hidden=false;
   detail.replaceChildren(node('h4',month+' 明细'),node('p','正在读取该月明细…','hint'));
   return Promise.resolve(api('/statement?month='+encodeURIComponent(month)))
    .then(vo=>{
     if(mine!==state.seq||state.collapsed)return 'discarded';
     if(!vo||vo.code)return fail(month,(vo&&vo.code)?'服务返回错误（'+vo.code+'）':'没有取得该月数据');
     if(monthDetailModel(vo,month,money).error)return fail(month,'服务返回错误（'+monthDetailModel(vo,month,money).error+'）');
     show(month,vo);return 'shown';
    },error=>{
     if(mine!==state.seq||state.collapsed)return 'discarded';
     return fail(month,(error&&(error.message||error.code))||'读取失败');
    });
  }
  function show(month,vo){
   if(state.collapsed)return;
   const model=monthDetailModel(vo,month,money);
   const head=node('div',undefined,'year-detail-head');
   head.append(node('h4',model.month+' 明细'));
   const close=node('button','收起明细','link-button');close.type='button';
   close.onclick=()=>{state.seq++;state.collapsed=true;state.month=null;detail.replaceChildren();detail.hidden=true;};
   head.append(close);
   detail.replaceChildren(head,node('p','支出 '+model.expense+' · 收入 '+model.income+' · 内部转账 '+model.internal+'（内部转账不计入收支）','hint'));
   if(model.empty){detail.append(node('p','这个月份没有已记录交易。','hint'));return;}
   for(const row of model.rows)detail.append(node('p',row.booked+' · '+row.merchant+' · '+row.direction+' · '+row.amount,'transaction-row'));
  }
  function fail(month,message){
   const box=node('div');
   box.append(node('p',month+' 明细读取失败：'+message+'。年度汇总仍可用。','hint'));
   const retry=node('button','重试','secondary');retry.type='button';retry.onclick=()=>open(month);
   box.append(retry);
   detail.replaceChildren(node('h4',month+' 明细'),box);
   return 'failed';
  }
  return {open,state,list,detail};
 };
}
