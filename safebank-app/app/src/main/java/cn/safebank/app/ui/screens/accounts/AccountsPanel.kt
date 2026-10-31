package cn.safebank.app.ui.screens.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.EmptyState
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.MoneyText
import cn.safebank.app.ui.components.TextActionButton
import cn.safebank.app.ui.theme.SafeBankColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 账户与余额面板（ui-design-spec §3.3、§4.2）。
 *
 * - 数据：`GET /api/accounts`（`AccountVo.id/label/balance_minor`，金额整数分）；
 * - 布局：手机单列、≥600dp 两列，间距 12dp；**首卡深蓝**（主账户视觉），其余浅蓝白底；
 * - 状态：加载中 / 加载失败（横幅 + 重试）/ 空态 / 已加载（标题行「刷新」可手动刷新）；
 * - 账户卡标签统一为「{账户名} · 合成账户」（文案 A-14）。
 */
@Composable
fun AccountsPanel(
    repository: SafeBankRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember { AccountsStateHolder(scope, repository) }
    LaunchedEffect(Unit) { holder.load() }

    val accounts = holder.accounts
    val loading = holder.loading
    val error = holder.error

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "账户余额",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextActionButton(
                    text = "刷新",
                    onClick = holder::load,
                    enabled = !loading,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            when {
                loading && accounts.isEmpty() -> LoadingRow(text = "正在读取账户…")
                error != null && accounts.isEmpty() -> InfoBanner(
                    text = "账户加载失败：$error",
                    tone = BannerTone.Error,
                    action = { TextActionButton(text = "重试", onClick = holder::load) },
                )
                accounts.isEmpty() -> EmptyState(
                    title = "暂无账户信息。",
                    hint = "请确认服务器地址与后端会话状态后刷新。",
                )
                else -> {
                    if (error != null) {
                        InfoBanner(
                            text = "账户刷新失败：$error",
                            tone = BannerTone.Error,
                            action = { TextActionButton(text = "重试", onClick = holder::load) },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    AccountGrid(accounts = accounts)
                }
            }
        }
    }
}

/**
 * 账户卡片网格：手机 1 列、平板（≥600dp）2 列。
 * 首张卡片用深蓝底白字突出"主账户"，其余卡片浅蓝底正文色（§3.3）。
 */
@Composable
private fun AccountGrid(accounts: List<AccountVo>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            accounts.chunked(columns).forEachIndexed { rowIndex, rowAccounts ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowAccounts.forEachIndexed { columnIndex, account ->
                        AccountCard(
                            account = account,
                            highlighted = rowIndex == 0 && columnIndex == 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowAccounts.size < columns) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountVo,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (highlighted) SafeBankColors.headingBlue else MaterialTheme.colorScheme.surfaceContainer
    val foreground = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryForeground = if (highlighted) Color(0xFFE3EBFF) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "${account.label} · 合成账户",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryForeground,
            )
            Spacer(modifier = Modifier.height(12.dp))
            MoneyText(
                minor = account.balanceMinor,
                color = foreground,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1).sp,
                ),
            )
        }
    }
}

/**
 * 账户面板状态：`remember { AccountsStateHolder(scope, repository) }` + Compose state。
 * 首次进入先 `ensureSession()` 再 `accounts()`；失败只记录中文错误，不抛异常。
 */
private class AccountsStateHolder(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
) {
    var accounts by mutableStateOf<List<AccountVo>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            // 会话失败时不中断，让 accounts() 的失败信息统一展示（避免页面白屏）。
            repository.ensureSession()
            repository.accounts().fold(
                onSuccess = { accounts = it },
                onFailure = { failure -> error = errorMessage(failure) },
            )
            loading = false
        }
    }

    private fun errorMessage(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: "请求未完成"
}
