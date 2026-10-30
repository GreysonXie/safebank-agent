/* 理财产品：只读目录查询、同字段对比、请助手解释、按月历史表现。
   数据只来自服务端目录（单一数据源），展示用规范 display_name，全部文本按 textContent 渲染。 */
function wealthTerm(product){
 if(!product)return '未提供';
 if(product.term_days===null||product.term_days===undefined)return product.term_note||'无固定产品到期日';
 return Number(product.term_days)+' 天';
}
function wealthHolding(product){
 if(!product||product.min_holding_days===null||product.min_holding_days===undefined)return '未提供';
 return Number(product.min_holding_days)===0?'无最低持有期':Number(product.min_holding_days)+' 个自然日';
}
function wealthForm(product){
 if(!product)return '未提供';
 const parts=[product.product_type,product.operation_mode].filter(Boolean);
 return parts.length?parts.join(' · '):'未提供';
}
function wealthName(product){return product?(product.display_name||product.name||product.id):'未提供';}
function wealthRisk(product){
 if(!product)return '未提供';
 return (product.risk_level||'未提供')+(product.risk_note?'：'+product.risk_note:'');
}
function wealthReturn(product){
 if(!product)return '未提供';
 return product.expected_return==='未提供'?'未提供'+(product.return_note?'（'+product.return_note+'）':''):String(product.expected_return);
}
function wealthCompareRows(catalog,a,b,money){
 const products=(catalog&&Array.isArray(catalog.products))?catalog.products:[];
 const left=products.find(item=>item.id===a),right=products.find(item=>item.id===b);
 if(!left||!right)return {error:'请选择本目录中的两个产品'};
 return {rows:[
  {field:'产品形态',left:wealthForm(left),right:wealthForm(right)},
  {field:'风险等级及说明',left:wealthRisk(left),right:wealthRisk(right)},
  {field:'最低持有期',left:wealthHolding(left),right:wealthHolding(right)},
  {field:'产品期限',left:wealthTerm(left),right:wealthTerm(right)},
  {field:'起购额',left:money(left.min_amount_minor),right:money(right.min_amount_minor)},
  {field:'赎回限制',left:left.redemption_rule||'未提供',right:right.redemption_rule||'未提供'},
  {field:'收益信息',left:wealthReturn(left),right:wealthReturn(right)},
  {field:'到账与费用',left:[left.settlement_note,left.fee_note].filter(Boolean).join('；')||'未提供',right:[right.settlement_note,right.fee_note].filter(Boolean).join('；')||'未提供'},
 ],left,right};
}
function historySummary(data,money){
 const percent=Number(data.change_percent);
 const sign=percent>0?'+':percent<0?'-':'';
 return {
  title:String(data.display_name||data.product),
  range:'区间 '+data.actual_from+' 至 '+data.actual_to+'（共 '+data.records+' 条样本；请求 '+data.requested_from+' 至 '+data.requested_to+'）',
  change:'单位净值 '+data.nav_from+' → '+data.nav_to+' 元/份，区间变化 '+sign+Math.abs(percent).toFixed(2)+'%',
  drawdown:Number(data.max_drawdown_percent)>0
    ?'区间最大回撤 '+Number(data.max_drawdown_percent).toFixed(2)+'%（峰 '+data.drawdown_peak_date+' '+data.drawdown_peak_nav+' → 谷 '+data.drawdown_trough_date+' '+data.drawdown_trough_nav+'）'
    :'该样本区间未出现回撤',
  note:String(data.coverage_note||''),
 };
}function createWealthPanel(deps){
 const {node,money,api,askChat}=deps;
 return function mount(host){
  const status=node('p','尚未查看产品。','hint');
  const query=node('button','查看产品','primary');query.type='button';
  const list=node('div',undefined,'wealth-list');
  const compare=node('section',undefined,'wealth-compare');
  const explain=node('button','请助手解释','secondary');explain.type='button';explain.disabled=true;
  const explainHint=node('p','选两个产品后可请助手解释差异。','hint');
  host.append(query,status,list,compare,explain,explainHint);
  let current=null,seq=0,explaining=false;
  query.onclick=async()=>{
   status.textContent='正在读取产品…';
   try{
    const catalog=await api('/wealth/products');
    current=catalog;seq++;
    status.textContent='共 '+(catalog.count||0)+' 个产品。';
    list.replaceChildren();
    for(const product of catalog.products||[]){
     const row=node('label',undefined,'wealth-row');
     const box=node('input');box.type='checkbox';box.value=product.id;
     row.append(box,node('span',wealthName(product)+' · '+(product.id||'')+' · '+(product.risk_level||'未提供')+' · '+(product.term_note||wealthTerm(product))+' · 起购 '+money(product.min_amount_minor)));
     list.append(row);
    }
    showCompare();
   }catch(error){
    current=null;seq++;
    status.textContent='读取产品失败：'+((error&&(error.message||error.code))||'请重试');
    list.replaceChildren();compare.replaceChildren();
    explain.disabled=true;explainHint.textContent='选两个产品后可请助手解释差异。';
   }
  };
  function selected(){
   return Array.from(list.querySelectorAll('input')).filter(box=>box.checked).map(box=>box.value);
  }
  function showCompare(){
   seq++;
   const boxes=selected();
   explain.disabled=boxes.length!==2;
   explainHint.textContent=boxes.length===2?'可以请助手解释这两个产品的差异。':'选两个产品后可请助手解释差异。';
   if(boxes.length!==2){compare.replaceChildren(node('p','勾选两个产品可查看同字段对比。','hint'));return;}
   const model=wealthCompareRows(current,boxes[0],boxes[1],money);
   if(model.error){compare.replaceChildren(node('p',model.error,'hint'));return;}
   const table=node('table',undefined,'wealth-table');
   const head=node('tr');head.append(node('th','对比项'),node('th',wealthName(model.left)),node('th',wealthName(model.right)));table.append(head);
   for(const row of model.rows){const tr=node('tr');tr.append(node('td',row.field),node('td',row.left),node('td',row.right));table.append(tr);}
   compare.replaceChildren(node('h3','产品对比'),table);
  }
  list.addEventListener('change',()=>showCompare());
  explain.onclick=async()=>{
   if(explaining)return;
   const boxes=selected();
   if(boxes.length!==2){explainHint.textContent='请先选择两个产品。';return;}
   if(!current||!current.catalog_revision){explainHint.textContent='请先查看产品以取得最新目录版本。';return;}
   const mine=++seq;explaining=true;explain.disabled=true;explainHint.textContent='正在核对所选产品…';
   try{
    const checked=await api('/wealth/validate?ids='+encodeURIComponent(boxes.join(','))+'&revision='+encodeURIComponent(current.catalog_revision));
    if(mine!==seq)return;
    const names=(checked.products||[]).map(item=>item.display_name||item.id).join('和');
    const ids=(checked.products||[]).map(item=>item.id).join('、');
    const result=askChat('请解释'+names+'的关键差异、主要限制和不确定项（产品编号：'+ids+'）。');
    explainHint.textContent=(result&&result.sent)===false
      ?(result.reason||'聊天区暂时不可用，请稍后再试。')
      :'已把这两个产品交给助手解释；下方对比表可继续核对。';
   }catch(error){
    if(mine!==seq)return;
    if(error&&error.code==='CATALOG_STALE')explainHint.textContent='产品目录已更新，请重新点“查看产品”后再对比；当前表格为旧数据，未发送解释请求。';
    else explainHint.textContent='所选产品校验未通过，未发送解释请求：'+((error&&(error.message||error.code))||'请重试');
   }finally{if(mine===seq){explaining=false;explain.disabled=selected().length!==2;}}
  };
  // --- wealth-history-month:begin ---
  // 历史月份默认跟着真实日期（本月），不固定在某个历史月份；可用上一个月按钮回退。
  function currentMonthValue(now){const date=now?new Date(now):new Date();return date.getFullYear()+'-'+String(date.getMonth()+1).padStart(2,'0');}
  function shiftMonthValue(value,delta){
   const match=/^(\d{4})-(\d{2})$/.exec(String(value||''));if(!match)return currentMonthValue();
   const date=new Date(Number(match[1]),Number(match[2])-1+delta,1);
   return date.getFullYear()+'-'+String(date.getMonth()+1).padStart(2,'0');
  }
  // --- wealth-history-month:end ---
  const history=node('section',undefined,'wealth-history');
  const historyMonth=node('input');historyMonth.type='month';historyMonth.value=currentMonthValue();
  const historyPrev=node('button','上一个月','ghost');historyPrev.type='button';
  const historyButton=node('button','查看历史表现','secondary');historyButton.type='button';
  const historyHint=node('p','选择产品后可按月查看单位净值区间表现。','hint');
  host.append(node('h3','历史表现'),historyPrev,historyMonth,historyButton,historyHint,history);
  historyPrev.onclick=()=>{historyMonth.value=shiftMonthValue(historyMonth.value,-1);};
  historyButton.onclick=async()=>{
   const picked=selected();
   if(picked.length!==1){historyHint.textContent='请先勾选一个产品再查看历史表现。';return;}
   historyHint.textContent='正在读取历史样本…';
   try{
    const result=await api('/wealth/history?productId='+encodeURIComponent(picked[0])+'&month='+encodeURIComponent(historyMonth.value));
    const model=historySummary(result,money);
    const head=node('p',model.title,'transaction-row');
    history.replaceChildren(head,node('p',model.range,'hint'),node('p',model.change,'transaction-row'),node('p',model.note,'hint'));
   }catch(error){
    history.replaceChildren(node('p','读取历史表现失败：'+((error&&(error.message||error.code))||'请重试'),'hint'));
    historyHint.textContent='';
   }
  };
  return {query,list,compare,explain,explainHint,status,showCompare,history,historyMonth,historyButton,historyHint};
 };
}
