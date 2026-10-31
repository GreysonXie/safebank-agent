package cn.safebank.app.ui.screens.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「待确认操作与回执」二级页（ui-design-spec §4.7 / §3.6 / §3.16）。
 *
 * 初始化：`GET /api/audit` 取最近的动作编号（去重，前 12 个），逐个 `GET /api/actions/{id}`
 * 回填卡片；终态卡（成功/已取消）折叠进「历史记录」。
 *
 * 卡片逻辑与对话页共用 [ActionCardState] / [ActionCard]，不重复实现。
 */
@Composable
fun ActionsRoute(navigator: AppNavigator, container: AppContainer) {
    val holder = remember { ActionsStateHolder(container.repository) }
    LaunchedEffect(Unit) { holder.load() }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(navigator, "待确认操作与回执")
        HeaderBar(holder)
        holder.errorText?.let { message ->
            ErrorBanner(message = message, onRetry = { holder.load() }, onDismiss = { holder.clearError() })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (holder.loading && holder.cards.isEmpty()) {
                item(key = "loading") { LoadingHint("正在读取最近的操作…") }
            }
            items(holder.pending, key = { "pending-${it.id}" }) { card ->
                ActionCard(card = card)
            }
            if (!holder.loading && holder.pending.isEmpty()) {
                item(key = "empty") { EmptyActions(hasHistory = holder.history.isNotEmpty()) }
            }
            if (holder.history.isNotEmpty()) {
                item(key = "history-toggle") { HistoryToggle(holder) }
                if (holder.historyExpanded) {
                    items(holder.history, key = { "history-${it.id}" }) { card ->
                        ActionCard(card = card)
                    }
                }
            }
            item(key = "history-empty-note") { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun HeaderBar(holder: ActionsStateHolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "待确认操作与回执",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "操作前逐项确认",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { holder.load() }, enabled = !holder.loading) {
            Text(if (holder.loading) "读取中…" else "刷新", fontSize = 12.sp)
        }
    }
}

@Composable
private fun HistoryToggle(holder: ActionsStateHolder) {
    TextButton(
        onClick = { holder.historyExpanded = !holder.historyExpanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "历史记录 · 已完成或已取消（${holder.history.size} 条）" +
                if (holder.historyExpanded) " ▾" else " ▸",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyActions(hasHistory: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "暂无待确认操作", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "操作预览会出现在这里，请核对对象、金额与时间后再确认。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hasHistory) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "暂无待确认操作。已完成记录可在下方展开查看。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
                TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * 待确认操作页状态：审计日志 → 动作详情 → 卡片。
 */
class ActionsStateHolder(private val repository: SafeBankRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val cards = mutableStateListOf<ActionCardState>()

    var loading by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var historyExpanded by mutableStateOf(false)

    val pending: List<ActionCardState> get() = cards.filter { !it.isArchived }
    val history: List<ActionCardState> get() = cards.filter { it.isArchived }

    fun clearError() {
        errorText = null
    }

    fun load() {
        if (loading) return
        loading = true
        errorText = null
        scope.launch {
            repository.audit().fold(
                onSuccess = { events ->
                    val ids = events.asSequence()
                        .map { it.actionId }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(MAX_CARDS)
                        .toList()
                    val loaded = mutableListOf<ActionCardState>()
                    var firstFailure: Throwable? = null
                    for (id in ids) {
                        repository.action(id).fold(
                            onSuccess = { receipt ->
                                val card = ActionCardState(scope, repository, receipt.toActionVo())
                                card.applyResult(receipt, fromSubmit = false)
                                loaded.add(card)
                            },
                            onFailure = { failure -> if (firstFailure == null) firstFailure = failure },
                        )
                    }
                    cards.clear()
                    cards.addAll(loaded)
                    val failed = firstFailure
                    if (loaded.isEmpty() && ids.isNotEmpty() && failed != null) {
                        errorText = "读取操作详情失败：${failureText(failed)}"
                    }
                },
                onFailure = { failure ->
                    errorText = "读取审计事件失败：${failureText(failure)}"
                },
            )
            loading = false
        }
    }

    private companion object {
        const val MAX_CARDS = 12
    }
}
