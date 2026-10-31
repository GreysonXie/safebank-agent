(() => {
 let providers=[],provider=null,saved=null,live=[],manual=[],revision=0,busy=false;
 const captions={preset:'参考预设',live:'接口返回',manual:'手动填写'};
 function icon(id){const img=node('img');img.src='/assets/models/'+({zhipu:'glm',moonshot:'kimi',siliconcloud:'siliconflow'}[id]||id)+'.svg';img.alt='';img.width=img.height=24;img.onerror=()=>{img.onerror=null;img.src='/assets/models/custom.svg';};return img;}
 function modelIcon(name){const value=name.toLowerCase();return value.includes('deepseek')?'deepseek':/qwen|qwq/.test(value)?'qwen':/glm|chatglm/.test(value)?'glm':/kimi|moonshot/.test(value)?'kimi':/gpt|o[134](?:-|$)|chatgpt/.test(value)?'openai':value.includes('doubao')?'doubao':value.includes('minimax')?'minimax':value.includes('hunyuan')?'hunyuan':provider.icon;}
 function values(){return {provider:provider.id,baseUrl:$('model-base-url').value.trim(),apiKey:$('model-api-key').value.trim(),model:$('model-name').value.trim(),enabled:true,clearApiKey:false};}
 function result(message,state=''){const el=$('model-test-result');el.textContent=message;el.className='model-test-result '+state;}
 function changed(){revision++;$('model-config-state').textContent='有未保存的配置';result('配置已修改。可以先检测，再保存并应用。');}
 function providersUI(){const root=$('model-providers');root.replaceChildren(...providers.map(p=>{const b=node('button',undefined,'model-provider');b.type='button';b.setAttribute('aria-pressed',String(p.id===provider.id));b.append(icon(p.icon),node('span',p.name));b.onclick=()=>{if(busy||p.id===provider.id)return;provider=p;live=[];manual=[];$('model-base-url').value=p.baseUrl;$('model-name').value=p.models[0]||'';$('model-api-key').value='';$('model-search').value='';$('model-list-source').value='all';providersUI();modelsUI();keyNote();changed();};return b;}));}
 function sameHost(a,b){try{return new URL(a).host===new URL(b).host;}catch{return false;}}
 function keyNote(){
  const base=$('model-base-url').value.trim(),hasSaved=!!saved&&saved.hasApiKey,hint=saved?.apiKeyHint||'';
  const same=hasSaved&&sameHost(saved.baseUrl,base);
  $('model-key-note').textContent=hasSaved&&hint
   ?'已保存密钥 '+hint+'（只显示末四位，明文不会回传，也无法从页面复制）。'+(same?'留空即沿用当前密钥，不必每次重新粘贴。':'注意：接口主机已变更，保存或检测时需要重新填写该主机的密钥。')
   :'请填写此供应商 / 地址的密钥。密钥只在本机后端加密保存，界面只显示末四位。';
  $('model-api-key').placeholder=hasSaved&&hint?('已保存 '+hint+'，留空沿用'):'输入密钥；已保存时留空可保留';
 }
 function modelsUI(){
  const selected=$('model-name').value.trim();if(selected&&!provider.models.includes(selected)&&!live.includes(selected)&&!manual.includes(selected))manual.push(selected);
  const items=new Map();for(const [source,ids] of [['preset',provider.models],['manual',manual],['live',live]])for(const id of ids)items.set(id,source);
  const query=$('model-search').value.trim().toLowerCase(),source=$('model-list-source').value;
  const rows=[...items].filter(([id,kind])=>(source==='all'||kind===source)&&id.toLowerCase().includes(query));
  $('model-list').replaceChildren(...rows.slice(0,120).map(([id,kind])=>{const b=node('button',undefined,'model-option');b.type='button';b.setAttribute('aria-pressed',String(id===selected));const text=node('span');text.append(node('strong',id),node('small',captions[kind]));b.append(icon(modelIcon(id)),text);b.onclick=()=>{if(busy)return;$('model-name').value=id;changed();modelsUI();};if(kind!=='manual')return b;const row=node('div',undefined,'model-option-row'),remove=node('button','删除','model-option-delete');remove.type='button';remove.setAttribute('aria-label','删除手动模型 '+id);remove.title='删除手动模型';remove.onclick=()=>{if(busy)return;manual=manual.filter(model=>model!==id);if($('model-name').value.trim()===id){$('model-name').value='';changed();}modelsUI();};row.append(b,remove);return row;}));
  if(!rows.length)$('model-list').append(node('p','没有匹配的模型，可在下方填写完整模型 ID。','hint'));
  $('model-list-note').textContent=(live.length?'已获取 '+live.length+' 个模型。':'预设仅供填写参考，账户权限以接口和检测结果为准。')+(rows.length>120?' 当前显示前 120 项，请搜索缩小范围。':'');
 }
 async function sessionMode(){const s=await api('/session');setMode(s.mode,s.model);await refreshModelBudget();}
 function savedUI(config){saved=config;provider=providers.find(p=>p.id===config.provider)||providers[0];$('model-base-url').value=config.baseUrl;$('model-name').value=config.model;$('model-api-key').value='';$('model-api-key').type='password';$('model-key-toggle').textContent='显示';$('model-config-state').textContent=!config.enabled?'离线演示':config.verified?'已保存 · 检测通过':'已保存 · 待检测';providersUI();keyNote();modelsUI();}
 async function run(button,fn){if(busy)return;busy=true;const controls=[...document.querySelectorAll('#view-models button,#view-models input,#view-models select')];controls.forEach(el=>el.disabled=true);try{await fn();}catch(error){result(error.message,'is-error');notify(error.message);}finally{busy=false;controls.filter(el=>el.isConnected).forEach(el=>el.disabled=false);document.querySelectorAll('#view-models button').forEach(el=>el.disabled=false);}}
 async function init(){
  try{[providers,saved]=await Promise.all([api('/models/providers'),api('/models/configuration')]);savedUI(saved);}catch(e){result('模型配置读取失败：'+e.message,'is-error');$('model-config-state').textContent='读取失败';return;}
  $('model-key-toggle').onclick=()=>{const input=$('model-api-key'),show=input.type==='password';input.type=show?'text':'password';$('model-key-toggle').textContent=show?'隐藏':'显示';$('model-key-toggle').setAttribute('aria-label',show?'隐藏密钥':'显示密钥');};
  for(const id of ['model-base-url','model-api-key','model-name'])$(id).oninput=()=>{changed();if(id==='model-base-url')keyNote();if(id==='model-name')modelsUI();};
  $('model-search').oninput=modelsUI;$('model-list-source').onchange=modelsUI;
  $('model-discover').onclick=()=>perform($('model-discover'),()=>run($('model-discover'),async()=>{result('正在获取此接口可见的模型…');const r=await api('/models/discover',values());live=r.models;modelsUI();result('模型列表已更新。选择模型后可检测实际调用权限。');}));
  $('model-test').onclick=()=>perform($('model-test'),()=>run($('model-test'),async()=>{if(!$('model-config-form').reportValidity())return;const current=revision;result('正在发送简短测试请求，请稍候…');const r=await api('/models/test',values());if(current===revision){const keyPart=r.keySource==='saved'?'使用已保存密钥 '+(r.keyHint||''):'使用本次输入的密钥';result('检测通过 · '+r.model+' · '+r.latencyMs+' ms · '+keyPart+'（'+r.host+'）。'+r.message,'is-success');$('model-config-state').textContent='当前填写的配置 · 检测通过';}}));
  $('model-config-form').onsubmit=e=>{e.preventDefault();perform(e.submitter,()=>run(e.submitter,async()=>{const r=await api('/models/configuration',values());savedUI(r);revision++;result(r.verified?'配置已保存，检测已通过。后续请求使用此模型。':'配置已保存。建议点击检测，确认账户与模型的调用权限。',r.verified?'is-success':'');await sessionMode();notify('模型配置已应用');}));};
  $('model-disable').onclick=()=>perform($('model-disable'),()=>run($('model-disable'),async()=>{const r=await api('/models/disable',{});savedUI(r);result('已切换到离线演示。保存的配置可供下次重新启用。');await sessionMode();}));
 }
 window.modelUI={init};
})();
