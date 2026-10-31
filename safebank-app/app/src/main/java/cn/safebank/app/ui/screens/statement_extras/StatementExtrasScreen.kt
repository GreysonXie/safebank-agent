package cn.safebank.app.ui.screens.statement_extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.StatementFilterVo
import cn.safebank.app.data.model.YearStatementVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.FormField
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.SecondaryButton
import cn.safebank.app.ui.components.SectionCard
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar
import kotlinx.coroutines.launch

/**
 * 账单扩展（t020）：
 * - 年度账单 `GET /api/statement/year?year=`
 * - 单月组合筛选 `GET /api/statement/filter?month&category&direction&minAmountMinor`
 * - 可用月份/年份 `GET /api/statement/months`、`/api/statement/years`
 */
@Composable
fun StatementExtrasRoute(navigator: AppNavigator, container: AppContainer) {
    DetailTopBar(navigator, "账单扩展")
    val scope = rememberCoroutineScope()
    val repo = container.repository

    var months by remember { mutableStateOf<List<String>>(emptyList()) }
    var years by remember { mutableStateOf<List<String>>(emptyList()) }

    var yearInput by remember { mutableStateOf(Formatters.currentMonth().substring(0, 4)) }
    var yearLoading by remember { mutableStateOf(false) }
    var yearError by remember { mutableStateOf<String?>(null) }
    var yearResult by remember { mutableStateOf<YearStatementVo?>(null) }

    var monthInput by remember { mutableStateOf("2026-09") }
    var category by remember { mutableStateOf("全部") }
    var direction by remember { mutableStateOf("支出") }
    var minYuan by remember { mutableStateOf("0") }
    var filterLoading by remember { mutableStateOf(false) }
    var filterError by remember { mutableStateOf<String?>(null) }
    var filterResult by remember { mutableStateOf<StatementFilterVo?>(null) }

    LaunchedEffect(Unit) {
        repo.ensureSession(false)
        repo.statementMonths().onSuccess { months = it }
        repo.statementYears().onSuccess { years = it }
    }

    fun loadYear() {
        val year = yearInput.trim()
        if (!Regex("^\\d{4}$").matches(year)) {
            yearError = "年份格式应为 YYYY，例如 2026。"
            return
        }
        yearLoading = true
        yearError = null
        yearResult = null
        scope.launch {
            repo.yearStatement(year)
                .onSuccess { yearResult = it }
                .onFailure { yearError = friendly(it) }
            yearLoading = false
        }
    }

    fun loadFilter() {
        val month = monthInput.trim()
        if (!Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(month)) {
            filterError = "月份格式应为 YYYY-MM，例如 2026-09。"
            return
        }
        val minMinor = Formatters.parseAmountToMinor(minYuan) ?: run {
            filterError = "单笔金额下限应为数字（元），最多两位小数。"
            return
        }
        filterLoading = true
        filterError = null
        filterResult = null
        scope.launch {
            repo.statementFilter(month, category.ifBlank { "全部" }, direction, minMinor)
                .onSuccess { filterResult = it }
                .onFailure { filterError = friendly(it) }
            filterLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "年度账单", subtitle = "每月一行，口径与月报一致；内部转账不计收支。") {
            FormField(label = "年份", value = yearInput, onValueChange = { yearInput = it.filter(Char::isDigit).take(4) })
            if (years.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    years.takeLast(4).forEach { y ->
                        AssistChip(onClick = { yearInput = y }, label = { Text(y) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "查询年度账单", onClick = { loadYear() }, enabled = !yearLoading)
            if (yearLoading) LoadingRow("正在汇总年度账单…")
            yearError?.let { InfoBanner(text = it, tone = BannerTone.Error) }
            yearResult?.let { vo ->
                Spacer(Modifier.height(8.dp))
                Text("${vo.year} 年合计", style = MaterialTheme.typography.titleMedium)
                Text("支出 ${Formatters.money(vo.expenseMinor)} · 收入 ${Formatters.money(vo.incomeMinor)} · 内部转账 ${Formatters.money(vo.internalTransferMinor)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("记录 ${vo.recordCount} 条" + (vo.coverage?.let { " · 业务日 ${it.businessDate}" + if (it.yearInProgress) " · 本年度进行中" else "" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                vo.months.forEach { row ->
                    Text(
                        "${row.month}  支出 ${Formatters.money(row.expenseMinor)}  收入 ${Formatters.money(row.incomeMinor)}  内部 ${Formatters.money(row.internalTransferMinor)}  ${row.transactionCount} 笔",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        SectionCard(title = "单月筛选", subtitle = "类别 + 收支方向 + 单笔金额下限；合计由服务端重算。") {
            FormField(label = "月份", value = monthInput, onValueChange = { monthInput = it.take(7) })
            if (months.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    months.takeLast(5).forEach { m ->
                        AssistChip(onClick = { monthInput = m }, label = { Text(m) })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            FormField(label = "类别（留空=全部）", value = category, onValueChange = { category = it })
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("支出", "收入", "全部").forEach { d ->
                    AssistChip(onClick = { direction = d }, label = { Text(d) })
                }
            }
            Spacer(Modifier.height(6.dp))
            FormField(label = "单笔金额下限（元）", value = minYuan, onValueChange = { minYuan = it.filter { c -> c.isDigit() || c == '.' }.take(12) })
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "筛选账单", onClick = { loadFilter() }, enabled = !filterLoading)
            if (filterLoading) LoadingRow("正在筛选…")
            filterError?.let { InfoBanner(text = it, tone = BannerTone.Error) }
            filterResult?.let { vo ->
                Spacer(Modifier.height(8.dp))
                Text("匹配 ${vo.matchedCount} 笔 · 支出 ${Formatters.money(vo.expenseMinor)} · 收入 ${Formatters.money(vo.incomeMinor)}",
                    style = MaterialTheme.typography.titleSmall)
                if (vo.listScopeNote.isNotBlank()) {
                    Text(vo.listScopeNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                vo.transactions.take(20).forEach { tx ->
                    val amount = tx.amountMinor
                    val signed = if (amount == null) "—" else Formatters.moneySigned(amount)
                    Text(
                        "${tx.merchant} · ${tx.category} · ${tx.bookedOn ?: "—"}  $signed",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (vo.transactions.isEmpty()) {
                    Text("没有匹配的交易。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun friendly(t: Throwable): String {
    val code = (t as? cn.safebank.app.data.network.ApiException)?.code
    return buildString {
        append(t.message ?: "请求失败")
        if (!code.isNullOrBlank()) append("（").append(code).append("）")
    }
}
