package cn.safebank.app.ui.screens.statement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.model.TransactionVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BadgeTone
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.Divider
import cn.safebank.app.ui.components.EmptyState
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.MoneyText
import cn.safebank.app.ui.components.PageHeader
import cn.safebank.app.ui.components.SectionCard
import cn.safebank.app.ui.components.SecondaryButton
import cn.safebank.app.ui.components.StatusBadge
import cn.safebank.app.ui.components.TextActionButton
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.screens.accounts.AccountsPanel
import cn.safebank.app.ui.theme.SafeBankColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 账单分析（ui-design-spec §4.2、§3.3、§3.4、§5.3）。
 *
 * 组成：页面标题卡（右侧「查看账户」开关）→ 账户面板（默认折叠）→ 月份切换
 * → 收支汇总（收入/支出双卡 + 净收支说明）→ 支出分类条形图 → 交易明细（默认 N≤6 展开）
 * → 异常候选（展示后端 anomaly_rule 文案）。
 *
 * 数据：首次进入先 `repository.ensureSession()` 再 `repository.statement(month)`；
 * 金额全部是整数分，`expense_minor` 后端已取绝对值，展示时**不要再次取负**。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun StatementRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val repository = container.repository
    val holder = remember { StatementStateHolder(scope, repository) }
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { holder.load() }

    // 在 LazyColumn DSL 之外读取 Compose state，保证状态变化触发重组。
    val statement = holder.statement
    val loading = holder.loading
    val error = holder.error

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "page-header") {
            PageHeader(
                title = "账单分析",
                subtitle = "先看清账单，再决定下一步。",
                action = {
                    SecondaryButton(
                        text = "查看账户",
                        onClick = { accountsExpanded = !accountsExpanded },
                        modifier = Modifier.semantics {
                            stateDescription = if (accountsExpanded) "已展开" else "已折叠"
                        },
                    )
                },
            )
        }

        if (accountsExpanded) {
            item(key = "accounts") {
                AccountsPanel(repository = repository)
            }
        }

        item(key = "month-switcher") { MonthSwitcherCard(holder) }

        if (error != null && statement != null) {
            item(key = "refresh-error") {
                InfoBanner(
                    text = "刷新失败：$error",
                    tone = BannerTone.Error,
                    action = { TextActionButton(text = "重试", onClick = holder::reload) },
                )
            }
        }

        when {
            statement == null && loading -> item(key = "loading") {
                LoadingRow(text = "正在加载账单…")
            }

            statement == null && error != null -> item(key = "load-error") {
                InfoBanner(
                    text = "账单加载失败：$error",
                    tone = BannerTone.Error,
                    action = { TextActionButton(text = "重试", onClick = holder::reload) },
                )
            }

            statement == null -> item(key = "empty") {
                EmptyState(
                    title = "还没有查询账单。",
                    hint = "在对话中发送“分析2026-09账单”，这里将展示查询依据。",
                )
            }

            else -> {
                item(key = "totals") { TotalsCard(statement) }
                item(key = "categories") { CategoryCard(statement) }
                item(key = "transactions") {
                    TransactionsCard(
                        statement = statement,
                        expanded = holder.detailsExpanded,
                        onToggle = holder::toggleDetails,
                    )
                }
                item(key = "anomalies") { AnomalyCard(statement) }
            }
        }

        item(key = "footer") {
            Text(
                text = "SafeBank v0.7 · 合成资金与身份 · 本地演示",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 月份切换

@Composable
private fun MonthSwitcherCard(holder: StatementStateHolder) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextActionButton(
                text = "上一月",
                onClick = holder::previousMonth,
                enabled = holder.canGoPrevious && !holder.loading,
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${holder.month} 账单",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (holder.loading) {
                    Text(
                        text = "正在加载…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextActionButton(
                text = "下一月",
                onClick = holder::nextMonth,
                enabled = holder.canGoNext && !holder.loading,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (holder.month != holder.currentMonth) {
                TextActionButton(
                    text = "回到本月",
                    onClick = holder::goToCurrentMonth,
                    enabled = !holder.loading,
                )
            }
            TextActionButton(
                text = "刷新",
                onClick = holder::reload,
                enabled = !holder.loading,
            )
        }
    }
}

// ---------------------------------------------------------------- 收支汇总

@Composable
private fun TotalsCard(statement: StatementVo) {
    SectionCard(title = "收支汇总") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryTile(
                label = "收入",
                amountMinor = statement.incomeMinor,
                background = SafeBankColors.incomeBg,
                foreground = SafeBankColors.incomeFg,
                modifier = Modifier.weight(1f),
            )
            SummaryTile(
                label = "支出",
                amountMinor = statement.expenseMinor,
                background = SafeBankColors.expenseBg,
                foreground = SafeBankColors.expenseFg,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "入账包含退款；内部转账不计入收支。净收支 " +
                Formatters.money(statement.incomeMinor - statement.expenseMinor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (statement.internalTransferMinor > 0L) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "已排除本人账户内部转账 " + Formatters.money(statement.internalTransferMinor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    amountMinor: Long,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
            )
            Spacer(modifier = Modifier.height(10.dp))
            MoneyText(
                minor = amountMinor,
                color = foreground,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- 支出分类

@Composable
private fun CategoryCard(statement: StatementVo) {
    val sorted = statement.categories.entries.sortedByDescending { it.value }
    SectionCard(title = "支出分类") {
        if (statement.expenseMinor <= 0L || sorted.isEmpty()) {
            Text(
                text = "这个月份没有查询到交易记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            sorted.forEachIndexed { index, entry ->
                CategoryBarRow(
                    name = entry.key,
                    amountMinor = entry.value,
                    totalMinor = statement.expenseMinor,
                )
                if (index != sorted.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

/** 分类行：90px 级标签列 / 12dp 进度轨 + 主色进度条 / 金额（§3.4）。 */
@Composable
private fun CategoryBarRow(
    name: String,
    amountMinor: Long,
    totalMinor: Long,
) {
    val fraction = if (totalMinor > 0L) {
        (amountMinor.toFloat() / totalMinor.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            modifier = Modifier.width(78.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = Formatters.money(amountMinor),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 交易明细

@Composable
private fun TransactionsCard(
    statement: StatementVo,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val count = statement.transactions.size
    // N ≤ 6 时默认展开，> 6 时收起并提供「展开 / 收起」（§3.4）。
    val actionContent: (@Composable () -> Unit)? = if (count > DETAIL_EXPAND_THRESHOLD) {
        { TextActionButton(text = if (expanded) "收起" else "展开", onClick = onToggle) }
    } else {
        null
    }
    SectionCard(title = "交易明细 · $count 笔", action = actionContent) {
        if (count == 0) {
            Text(
                text = "这个月份没有查询到交易记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (expanded) {
            statement.transactions.forEachIndexed { index, transaction ->
                TransactionRow(transaction = transaction)
                if (index != statement.transactions.lastIndex) {
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionVo,
    badge: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge != null) {
            StatusBadge(text = badge, tone = BadgeTone.Warning)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchant.ifBlank { "未知商户" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = listOfNotNull(
                transaction.bookedOn?.takeIf { it.isNotBlank() },
                transaction.category.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        val amount = transaction.amountMinor
        if (amount != null) {
            val amountColor = when {
                amount < 0L -> SafeBankColors.expenseFg
                amount > 0L -> SafeBankColors.incomeFg
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            MoneyText(
                minor = amount,
                signed = true,
                color = amountColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 异常候选

@Composable
private fun AnomalyCard(statement: StatementVo) {
    val rule: String? = statement.anomalyRule.takeIf { it.isNotBlank() }
    SectionCard(title = "异常候选", subtitle = rule) {
        if (statement.anomalyCandidates.isEmpty()) {
            Text(
                text = "暂无异常候选。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            statement.anomalyCandidates.forEachIndexed { index, transaction ->
                TransactionRow(transaction = transaction, badge = "大额")
                if (index != statement.anomalyCandidates.lastIndex) {
                    Divider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 状态

/** 与后端默认账单月份 / 种子数据一致的初始月份（设计稿示例为 2026-09）。 */
private const val INITIAL_MONTH = "2026-09"

/** 可回溯的最早月份，避免无限翻页。 */
private const val MIN_MONTH = "2025-01"

/** 交易明细默认展开阈值（§3.4：N ≤ 6 展开）。 */
private const val DETAIL_EXPAND_THRESHOLD = 6

/**
 * 账单页状态：`remember { StatementStateHolder(scope, repository) }` + Compose state。
 *
 * - 月份：YYYY-MM 字符串；范围 [MIN_MONTH, max(当前月, INITIAL_MONTH)]；
 * - 加载：首次进入（以及每次切换月份/刷新）先 `ensureSession()` 再 `statement(month)`；
 * - 错误：只记录中文 message，页面用横幅 + 重试展示；
 * - 交易明细展开态：加载成功后按 N ≤ 6 自动决定，用户可手动切换。
 */
private class StatementStateHolder(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
) {
    var month by mutableStateOf(INITIAL_MONTH)
        private set
    var statement by mutableStateOf<StatementVo?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var detailsExpanded by mutableStateOf(false)
        private set

    val currentMonth: String = Formatters.currentMonth()
    private val maxMonth: String = maxOf(currentMonth, INITIAL_MONTH)

    val canGoPrevious: Boolean get() = month > MIN_MONTH
    val canGoNext: Boolean get() = month < maxMonth

    fun load() {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            repository.ensureSession()
            val requestedMonth = month
            repository.statement(requestedMonth).fold(
                onSuccess = { data ->
                    if (requestedMonth == month) {
                        statement = data
                        detailsExpanded = data.transactions.size <= DETAIL_EXPAND_THRESHOLD
                        error = null
                    }
                },
                onFailure = { failure -> error = errorMessage(failure) },
            )
            loading = false
        }
    }

    fun reload() {
        load()
    }

    fun previousMonth() {
        selectMonth(shiftMonth(month, -1))
    }

    fun nextMonth() {
        selectMonth(shiftMonth(month, 1))
    }

    fun goToCurrentMonth() {
        selectMonth(currentMonth)
    }

    fun toggleDetails() {
        detailsExpanded = !detailsExpanded
    }

    private fun selectMonth(target: String) {
        if (loading) return
        val safeMonth = target.coerceIn(MIN_MONTH, maxMonth)
        if (safeMonth == month && statement != null) return
        month = safeMonth
        statement = null
        error = null
        load()
    }

    private fun shiftMonth(from: String, delta: Int): String = try {
        YearMonth.parse(from)
            .plusMonths(delta.toLong())
            .format(DateTimeFormatter.ofPattern("yyyy-MM"))
    } catch (exception: Exception) {
        from
    }

    private fun errorMessage(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: "请求未完成"
}
