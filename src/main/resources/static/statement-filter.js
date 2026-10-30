/* 单月组合筛选结果的展示模型：条件表头、方向对应的合计标签、无记录提示、逐笔方向。
   金额由服务端计算，这里只做展示映射；所有文本按 textContent 渲染。 */
function filterSummary(data,money){
 const income=data.direction==='收入';
 const min=Number(data.min_amount_minor||0);
 const condition=[data.month,data.category,income?'收入':'支出',min>0?'单笔超过 '+money(min):''].filter(Boolean).join(' · ');
 return {
  title:condition,
  totalLabel:income?'入账合计（含退款）':'支出合计',
  total:money(income?data.income_minor:data.expense_minor),
  matchesLabel:'匹配笔数',
  matches:Number(data.matched_count||0)+' 笔',
  note:String(data.list_scope_note||'合计按全部匹配记录计算。'),
  empty:!data.matched_count,
  emptyText:'该条件下的 '+condition+' 没有记录。',
  income,
 };
}
function filterRows(data){
 return (data&&Array.isArray(data.transactions)?data.transactions:[]).map(row=>{
  const amount=Number(row.amount_minor||0);
  const internal=row.category==='内部转账';
  return {booked:String(row.booked_on||''),merchant:String(row.merchant||''),
   direction:internal?'内部转账':amount<0?'支出':'收入',magnitude:Math.abs(amount)};
 });
}
