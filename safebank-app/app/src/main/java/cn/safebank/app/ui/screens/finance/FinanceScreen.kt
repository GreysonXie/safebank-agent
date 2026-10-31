package cn.safebank.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.FinanceProductVo
import cn.safebank.app.data.model.FinanceRecommendationRequest
import cn.safebank.app.data.model.FinanceRecommendationVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.SectionCard
import cn.safebank.app.ui.components.SecondaryButton
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar
import java.math.BigDecimal
import kotlinx.coroutines.launch

/**
 * 金融推荐（后端 `POST /api/finance/recommendations`）。
 *
 * t020 后端返回的全部是 **camelCase** 字段（FinanceProduct / FinanceRecommendationVO 均为 Java record）：
 * minimumAmountMinor / redemptionBusinessDays / liquidityNote / riskNote / periodReturnBps …
 * 金额输入用「元」，内部换算成整数分提交。
 *
 * 该功能只读：只返回本地合成示例产品的匹配结果，不产生真实申购、资金操作或模型调用。
 */
@Composable
fun FinanceRoute(navigator: AppNavigator, container: AppContainer) {
    DetailTopBar(navigator, "金融推荐")

    val scope = rememberCoroutineScope()
    val repo = container.repository
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<FinanceRecommendationVo?>(null) }

    // 表单状态：金额按「元」输入，提交时转整数分
    var riskPref by remember { mutableStateOf("CONSERVATIVE") }
    var purpose by remember { mutableStateOf("RESERVE") }
    var liquidity by remember { mutableStateOf("IMMEDIATE") }
    var amountYuan by remember { mutableStateOf("1000") }
    var horizonMonths by remember { mutableStateOf("6") }
    var capitalProtection by remember { mutableStateOf(false) }

    fun submit() {
        val amt = Formatters.parseAmountToMinor(amountYuan)
        if (amt == null || amt < 100L) {
            error = "请填写可投入金额（元），至少 1 元、最多两位小数。"
            return
        }
        if (amt > 100_000_000_00L) {
            error = "金额最多 1 亿元。"
            return
        }
        val hz = horizonMonths.toIntOrNull()
        if (hz == null || hz < 1 || hz > 120) {
            error = "持有期限须为 1–120 个月。"
            return
        }
        loading = true
        error = null
        result = null
        scope.launch {
            repo.financeRecommend(
                FinanceRecommendationRequest(
                    riskPreference = riskPref,
                    purpose = purpose,
                    liquidity = liquidity,
                    amountMinor = amt,
                    horizonMonths = hz,
                    capitalProtection = capitalProtection,
                )
            ).onSuccess { result = it }
                .onFailure { failure ->
                    val code = (failure as? cn.safebank.app.data.network.ApiException)?.code
                    error = buildString {
                        append(failure.message ?: "推荐请求失败")
                        if (!code.isNullOrBlank()) append("（").append(code).append("）")
                    }
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) { repo.ensureSession(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "示例理财产品筛选，不产生真实申购。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard(title = "推荐偏好") {
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    label = "风险偏好",
                    options = listOf("CONSERVATIVE" to "稳健", "BALANCED" to "平衡", "GROWTH" to "成长"),
                    selected = riskPref,
                    onSelect = { riskPref = it },
                )
                Spacer(Modifier.height(10.dp))
                ChipRow(
                    label = "资金用途",
                    options = listOf("RESERVE" to "备用金", "PLANNED" to "计划支出", "GROWTH" to "长期配置"),
                    selected = purpose,
                    onSelect = { purpose = it },
                )
                Spacer(Modifier.height(10.dp))
                ChipRow(
                    label = "流动性需求",
                    options = listOf("IMMEDIATE" to "随时支取", "FLEXIBLE" to "灵活取用", "LOCKABLE" to "可锁定期"),
                    selected = liquidity,
                    onSelect = { liquidity = it },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountYuan,
                        onValueChange = { input ->
                            amountYuan = input.filter { c -> c.isDigit() || c == '.' }.take(14)
                        },
                        label = { Text("可投入金额（元）") },
                        supportingText = { Text("例如 1000 或 1500.50") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = horizonMonths,
                        onValueChange = { horizonMonths = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("期限（月）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = capitalProtection, onCheckedChange = { capitalProtection = it })
                    Text("本金保障优先", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(text = "生成推荐", onClick = { submit() }, enabled = !loading)
            }
        }

        if (loading) {
            item { LoadingRow("正在筛选示例产品…") }
        }
        error?.let { message ->
            item { InfoBanner(text = message, tone = BannerTone.Error) }
        }

        result?.let { vo ->
            item {
                SectionCard(title = "推荐结果") {
                    Text(
                        text = vo.summary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    val meta = buildList {
                        if (vo.catalogVersion.isNotBlank()) add("目录 ${vo.catalogVersion}")
                        if (vo.source.isNotBlank()) add(vo.source)
                        if (vo.asOf.isNotBlank()) add("数据日 ${vo.asOf}")
                        if (vo.generatedAt.isNotBlank()) add("生成 ${Formatters.shortDateTime(vo.generatedAt)}")
                        if (vo.mode.isNotBlank()) add("模式 ${vo.mode}")
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(vo.candidates) { cand ->
                val p = cand.product
                SectionCard(title = "✓ ${p.name}", subtitle = p.category) {
                    ProductFacts(p)
                    if (cand.reasons.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        cand.reasons.forEach { reason ->
                            Text(
                                "• $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (vo.exclusions.isNotEmpty()) {
                item {
                    SectionCard(title = "未匹配方向", subtitle = "以下产品因不满足条件未进入候选") {
                        vo.exclusions.forEach { ex ->
                            val name = ex.productName.ifBlank { ex.productId.ifBlank { "未命名方向" } }
                            Text(
                                text = "• $name：${ex.reasons.joinToString("；")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = "筛选规则") {
                    vo.rules.forEach { rule ->
                        Text(
                            "• $rule",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = vo.disclosure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 候选产品的事实行：起投、风险、本金保障、存期/赎回、收益/回撤/年费、流动性与风险提示。 */
@Composable
private fun ProductFacts(p: FinanceProductVo) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        PillText("起投 ${Formatters.money(p.minimumAmountMinor)}")
        if (p.riskLabel.isNotBlank()) PillText("风险 ${p.riskLabel}")
        if (p.capitalProtected) PillText("本金保障")
        PillText(if (p.lockMonths > 0) "存期 ${p.lockMonths} 个月" else "赎回 T+${p.redemptionBusinessDays}")
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "区间收益 ${bpsText(p.periodReturnBps)} · 最大回撤 ${bpsText(p.maxDrawdownBps)} · 年费 ${bpsText(p.annualFeeBps)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (p.description.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            p.description,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (p.liquidityNote.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text("流动性：${p.liquidityNote}", style = MaterialTheme.typography.bodySmall)
    }
    if (p.riskNote.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
            "风险提示：${p.riskNote}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** bps（万分之一）→ 百分比文本；缺失显示「暂无数据」。例：250 → "2.5%"。 */
private fun bpsText(bps: Int?): String {
    if (bps == null) return "暂无数据"
    val text = BigDecimal.valueOf(bps.toLong(), 2).stripTrailingZeros().toPlainString()
    return "$text%"
}

@Composable
private fun ChipRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, display) ->
                AssistChip(
                    onClick = { onSelect(value) },
                    label = { Text(display) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PillText(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
