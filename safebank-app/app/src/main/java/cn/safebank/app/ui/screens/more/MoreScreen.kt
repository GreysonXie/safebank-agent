package cn.safebank.app.ui.screens.more

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.ui.navigation.AppDestination
import cn.safebank.app.ui.navigation.AppNavigator

/**
 * 「更多」入口页（tab）：会话/服务器摘要 + 4 个二级入口 + 版本与合成数据说明。
 * ui-spec §0.2：更多用于收纳低频入口（常用工具、实验与审计、设置）。
 */
@Composable
fun MoreRoute(navigator: AppNavigator, container: AppContainer) {
    var session by remember { mutableStateOf<SessionVo?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    // StateFlow 必须在组合内以 collectAsState 订阅（不能直接读 .value），否则设置页改地址后不重组
    val baseUrl by container.settings.baseUrl.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    LaunchedEffect(reloadTick) {
        loading = true
        sessionError = null
        container.repository.ensureSession(false)
            .onSuccess { session = it }
            .onFailure { error -> sessionError = friendlyError(error) }
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PageTitle(title = "更多", subtitle = "常用工具、实验与审计、设置。")

            SectionCard(title = "当前会话") {
                if (loading) {
                    LoadingRow("正在建立会话…")
                } else if (sessionError != null) {
                    InfoBanner(text = sessionError.orEmpty(), tone = Tone.Danger)
                    Spacer(modifier = Modifier.height(6.dp))
                    SecondaryActionButton(text = "重新建立会话", onClick = { reloadTick += 1 })
                } else {
                    val current = session
                    KeyValueRow("用户", current?.user.orEmpty().ifBlank { "—" })
                    KeyValueRow("会话模式", modeLabel(current?.mode))
                    KeyValueRow("模型", current?.model.orEmpty().ifBlank { "—" })
                    KeyValueRow("认证方式", current?.authentication.orEmpty().ifBlank { "—" })
                    KeyValueRow("服务器", baseUrl)
                    Spacer(modifier = Modifier.height(6.dp))
                    InfoBanner(text = modeDescription(current?.mode), tone = Tone.Info)
                    Spacer(modifier = Modifier.height(6.dp))
                    SecondaryActionButton(text = "刷新会话信息", onClick = { reloadTick += 1 })
                }
            }

            SectionCard(title = "工具与实验", subtitle = "银行操作都会先生成预览，再逐项授权。") {
                MoreMenuEntry(
                    title = "准备一笔转账",
                    subtitle = "填写来源、收款人与金额，先生成操作预览。",
                ) { navigator.push(AppDestination.Transfer) }
                MoreMenuEntry(
                    title = "金融推荐",
                    subtitle = "按风险偏好、期限与金额筛选示例理财方向。",
                ) { navigator.push(AppDestination.Finance) }
                MoreMenuEntry(
                    title = "卡片管理",
                    subtitle = "查看卡片、调整限额、开关在线/境外支付。",
                ) { navigator.push(AppDestination.Cards) }
                MoreMenuEntry(
                    title = "账单扩展",
                    subtitle = "年度账单与单月类别/方向/金额筛选。",
                ) { navigator.push(AppDestination.StatementExtras) }
                MoreMenuEntry(
                    title = "用户与联系人",
                    subtitle = "查看联系人、维护个人资料与新增联系人。",
                ) { navigator.push(AppDestination.Users) }
                MoreMenuEntry(
                    title = "待确认操作与回执",
                    subtitle = "查看最近的动作卡、授权与回执状态。",
                ) { navigator.push(AppDestination.Actions) }
                MoreMenuEntry(
                    title = "实验与审计",
                    subtitle = "审计事件、当日风险额度、模拟验证与故障注入。",
                ) { navigator.push(AppDestination.Audit) }
                MoreMenuEntry(
                    title = "设置",
                    subtitle = "服务器地址、会话信息与连通性自检。",
                ) { navigator.push(AppDestination.Settings) }
            }

            Text(
                text = "版本 $versionName · 合成银行数据 · 仅用于本地演示",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            )
        }
        PageToast(message = toast) { toast = null }
    }
}

@Composable
private fun MoreMenuEntry(title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modeDescription(mode: String?): String = when (mode) {
    "OFFLINE_DEMO" -> "离线工具演示：未设置模型密钥，对话采用有限规则匹配，写操作须逐项授权。"
    "CONNECTION_VERIFIED" -> "模型连接已验证：本次进程已获得有效响应；写操作仍须逐项授权。"
    "FAKE_AGENT" -> "网关协议模拟：验证只读工具、权限与恢复协议，未调用大模型。"
    "LANGGRAPH_MOCK" -> "LangGraph 离线模拟模型：正式对话图已接入，当前不消耗 API 额度。"
    "LANGGRAPH_MODEL" -> "LangGraph 模型已配置：模型调用有额度限制。"
    else -> "模型已配置 · 未验证：尚未获得有效连接响应；写操作须逐项授权。"
}
