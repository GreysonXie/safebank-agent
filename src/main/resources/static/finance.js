/* Read-only finance discovery. Product facts come exclusively from the recommendation response. */
(() => {
  let initialized = false, result = null, revision = 0;
  const selected = new Set();
  const percent = bps => bps == null ? '暂无数据' : (bps / 100).toFixed(2) + '%';
  const fields = ['purpose', 'risk', 'liquidity', 'amount', 'horizon', 'capital'];
  const presets = {
    reserve: {purpose: 'RESERVE', risk: 'CONSERVATIVE', liquidity: 'IMMEDIATE', amount: '10000', horizon: '3', capital: 'true'},
    planned: {purpose: 'PLANNED', risk: 'CONSERVATIVE', liquidity: 'FLEXIBLE', amount: '10000', horizon: '12', capital: 'false'},
    growth: {purpose: 'GROWTH', risk: 'GROWTH', liquidity: 'LOCKABLE', amount: '10000', horizon: '36', capital: 'false'}
  };

  function status(text, error = false) {
    $('finance-status').textContent = text;
    $('finance-status').classList.toggle('finance-error', error);
  }

  function resetResults(text) {
    revision++;
    result = null;
    selected.clear();
    $('finance-results').hidden = true;
    $('finance-comparison').hidden = true;
    $('finance-evidence').hidden = true;
    $('finance-placeholder').hidden = false;
    $('finance-placeholder-text').textContent = text;
    status('');
    document.querySelectorAll('[data-finance-preset]').forEach(button => button.setAttribute('aria-pressed', 'false'));
  }

  function preferences() {
    const amountMinor = amount($('finance-amount').value);
    const horizon = $('finance-horizon').value.trim();
    if (amountMinor < 100 || amountMinor > 10000000000) throw Error('可投入金额须为 1 元至 1 亿元。');
    if (!/^\d+$/.test(horizon) || Number(horizon) < 1 || Number(horizon) > 120) throw Error('持有期限须为 1 至 120 的整数月。');
    return {
      riskPreference: $('finance-risk').value,
      purpose: $('finance-purpose').value,
      liquidity: $('finance-liquidity').value,
      amountMinor,
      horizonMonths: Number(horizon),
      capitalProtection: $('finance-capital').value === 'true'
    };
  }

  function metric(label, value) {
    const box = node('div');
    box.append(node('dt', label), node('dd', value));
    return box;
  }

  function renderCandidate(candidate, index) {
    const p = candidate.product;
    const card = node('article', undefined, 'finance-card');
    const top = node('div', undefined, 'finance-card-top');
    const tags = node('div', undefined, 'finance-tags');
    tags.append(node('span', p.category, 'finance-category'), node('span', '示例层级 ' + p.riskLevel + ' · ' + p.riskLabel, 'finance-risk'));
    top.append(tags, node('span', String(index + 1).padStart(2, '0'), 'finance-index'));
    card.append(top, node('h3', p.name), node('p', p.description, 'finance-description'));
    const metrics = node('dl', undefined, 'finance-card-metrics');
    metrics.append(metric('区间收益 · 示例', percent(p.periodReturnBps)), metric('区间最大回撤 · 示例', percent(p.maxDrawdownBps)), metric('年费率 · 示例', percent(p.annualFeeBps)));
    const terms = node('p', money(p.minimumAmountMinor) + ' 起 · 建议至少 ' + p.minimumHorizonMonths + ' 个月 · ' + (p.capitalProtected ? '存款本金保障有条件' : '不保本'), 'finance-terms');
    const reasons = node('ul', undefined, 'finance-reasons');
    candidate.reasons.forEach(reason => reasons.append(node('li', reason)));
    const details = node('details', undefined, 'finance-risk-details');
    details.append(node('summary', '流动性与风险说明'), node('p', p.liquidityNote), node('p', p.riskNote));
    const choice = node('label', undefined, 'finance-compare-choice');
    const checkbox = node('input');
    checkbox.type = 'checkbox';
    checkbox.value = p.id;
    checkbox.setAttribute('aria-label', '加入对比：' + p.name);
    checkbox.onchange = () => {
      if (checkbox.checked && selected.size >= 3) {
        checkbox.checked = false;
        status('最多同时对比 3 个方向，请先移除一个。', true);
        return;
      }
      checkbox.checked ? selected.add(p.id) : selected.delete(p.id);
      status('已选择 ' + selected.size + ' 个方向。选择 2 至 3 个即可查看对比。');
      renderComparison();
    };
    choice.append(checkbox, node('span', '加入对比'));
    card.append(metrics, terms, reasons, details, choice);
    return card;
  }

  function renderComparison() {
    const candidates = result.candidates.filter(c => selected.has(c.product.id));
    $('finance-compare-count').textContent = selected.size + ' / 3';
    $('finance-comparison').hidden = candidates.length === 0;
    $('finance-compare-hint').textContent = candidates.length < 2 ? '再选择一个方向，即可并排比较。' : '同一区间比较，历史表现不代表未来收益；年费率不包含全部交易费用。';
    const table = $('finance-compare-table');
    table.replaceChildren();
    const caption = node('caption', '所选方向对比 · 示例区间 ' + result.periodStart + ' 至 ' + result.periodEnd);
    const head = node('thead'), header = node('tr');
    const corner = node('th', '比较项目');
    corner.scope = 'col';
    header.append(corner);
    candidates.forEach(({product: p}) => {
      const cell = node('th', p.name);
      cell.scope = 'col';
      header.append(cell);
    });
    head.append(header);
    const rows = [
      ['类别', p => p.category],
      ['示例风险层级', p => p.riskLevel + ' · ' + p.riskLabel],
      ['本金', p => p.capitalProtected ? '存款本金保障有条件' : '不保本，可能亏损'],
      ['区间收益 · 示例', p => percent(p.periodReturnBps)],
      ['最大回撤 · 示例', p => percent(p.maxDrawdownBps)],
      ['年费率 · 示例', p => percent(p.annualFeeBps)],
      ['起投金额', p => money(p.minimumAmountMinor)],
      ['建议持有期限', p => '至少 ' + p.minimumHorizonMonths + ' 个月'],
      ['约定存期', p => p.lockMonths ? p.lockMonths + ' 个月' : '无'],
      ['流动性', p => p.liquidityNote],
      ['主要风险', p => p.riskNote],
      ['数据来源', () => result.source + ' · ' + result.asOf]
    ];
    const body = node('tbody');
    rows.forEach(([label, value]) => {
      const row = node('tr'), th = node('th', label);
      th.scope = 'row';
      row.append(th);
      candidates.forEach(c => row.append(node('td', value(c.product))));
      body.append(row);
    });
    table.append(caption, head, body);
  }

  function render(response) {
    result = response;
    $('finance-placeholder').hidden = true;
    $('finance-results').hidden = false;
    $('finance-summary').textContent = response.summary;
    $('finance-data-date').textContent = '数据日 ' + response.asOf;
    $('finance-period').textContent = '示例指标区间：' + response.periodStart + ' 至 ' + response.periodEnd + '。年费率仅作示例，不含申赎等全部费用。';
    $('finance-disclosure').textContent = response.disclosure;
    $('finance-candidates').replaceChildren(...response.candidates.map(renderCandidate));
    $('finance-no-match').hidden = response.candidates.length !== 0;
    const rules = $('finance-rules');
    rules.replaceChildren(...response.rules.map(rule => node('li', rule)));
    $('finance-provenance').textContent = '来源：' + response.source + ' · 版本：' + response.catalogVersion + ' · 生成时间：' + new Date(response.generatedAt).toLocaleString('zh-CN');
    const exclusions = $('finance-exclusions');
    exclusions.replaceChildren(...response.exclusions.map(exclusion => {
      const item = node('li');
      item.append(node('strong', exclusion.productName), node('p', exclusion.reasons.join('；')));
      return item;
    }));
    $('finance-exclusions-empty').hidden = response.exclusions.length !== 0;
    $('finance-evidence').hidden = false;
    $('finance-evidence').open = false;
    $('finance-compare-count').textContent = '0 / 3';
    status(response.candidates.length ? '推荐已更新，可勾选 2 至 3 个方向进行比较。' : '暂无匹配方向，请调整偏好后重试。');
  }

  async function submit(event) {
    event.preventDefault();
    if ($('finance-submit').disabled) return;
    let request;
    try { request = preferences(); } catch (error) { status(error.message, true); return; }
    resetResults('正在按你的偏好筛选…');
    const currentRevision = revision;
    $('finance-fields').disabled = true;
    $('finance-submit').disabled = true;
    $('finance-submit').textContent = '正在筛选…';
    $('finance-form').setAttribute('aria-busy', 'true');
    document.querySelectorAll('[data-finance-preset]').forEach(button => button.disabled = true);
    status('正在建立推荐会话…');
    try {
      await ensureSession();
      status('正在检查风险、期限与流动性条件…');
      const response = await api('/finance/recommendations', request);
      if (currentRevision === revision) render(response);
    } catch (error) {
      $('finance-placeholder-text').textContent = '本次未获得推荐，请保留偏好并重新生成。';
      status('推荐未完成：' + (error.name === 'AbortError' ? '请求超时，请重试。' : error.message), true);
    } finally {
      $('finance-fields').disabled = false;
      $('finance-submit').disabled = false;
      $('finance-submit').textContent = '生成推荐';
      $('finance-form').setAttribute('aria-busy', 'false');
      document.querySelectorAll('[data-finance-preset]').forEach(button => button.disabled = false);
    }
  }

  window.financeUI = {
    init() {
      if (initialized) return;
      initialized = true;
      $('finance-form').onsubmit = submit;
      fields.forEach(key => $('finance-' + key).addEventListener('input', () => resetResults('偏好已更新，点击「生成推荐」查看新的匹配结果。')));
      document.querySelectorAll('[data-finance-preset]').forEach(button => button.onclick = () => {
        resetResults('场景偏好已填好，可继续调整，再生成推荐。');
        Object.entries(presets[button.dataset.financePreset]).forEach(([key, value]) => $('finance-' + key).value = value);
        button.setAttribute('aria-pressed', 'true');
      });
      $('finance-clear-comparison').onclick = () => {
        selected.clear();
        document.querySelectorAll('.finance-compare-choice input').forEach(input => input.checked = false);
        renderComparison();
        status('已清空对比，可重新选择方向。');
      };
      $('finance-to-savings').onclick = () => selectView('savings');
      $('finance-submit').disabled = false;
    }
  };
  window.financeUI.init();
})();
