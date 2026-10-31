package cn.safebank.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.BuildConfig
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.screens.more.DetailPageScaffold
import cn.safebank.app.ui.screens.more.EmptyStateBlock
import cn.safebank.app.ui.screens.more.FormField
import cn.safebank.app.ui.screens.more.InfoBanner
import cn.safebank.app.ui.screens.more.KeyValueRow
import cn.safebank.app.ui.screens.more.PageToast
import cn.safebank.app.ui.screens.more.PrimaryActionButton
import cn.safebank.app.ui.screens.more.SecondaryActionButton
import cn.safebank.app.ui.screens.more.SectionCard
import cn.safebank.app.ui.screens.more.Tone
import cn.safebank.app.ui.screens.more.friendlyError
import cn.safebank.app.ui.screens.more.maskSecret
import cn.safebank.app.ui.screens.more.modeLabel
import kotlinx.coroutines.launch

/**
 * 「设置」二级页：服务器地址编辑（AppSettings.baseUrl）+ 连通性自检 + 会话信息 + 使用说明。
 *
 * 地址保存后不会自动迁移已有会话：必须重新建立会话（自检 force=true 会重建）。
 */
@Composable
fun SettingsRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val repository = container.repository
    var toast by remember { mutableStateOf<String?>(null) }

    var baseUrlInput by remember { mutableStateOf(container.settings.baseUrl.value) }
    var effectiveUrl by remember { mutableStateOf(container.settings.baseUrl.value) }
    var session by remember { mutableStateOf<SessionVo?>(repository.currentSession()) }
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestMessage by remember { mutableStateOf<String?>(null) }
    var selfTestTone by remember { mutableStateOf(Tone.Info) }
    var sessionBusy by remember { mutableStateOf(false) }

    val saveBaseUrl: () -> Unit = {
        val saved = container.settings.setBaseUrl(baseUrlInput)
        if (!saved) {
            toast = "服务器地址无效：请填写如 http://10.0.2.2:8080 的地址。"
        } else {
            baseUrlInput = container.settings.baseUrl.value
            effectiveUrl = container.settings.baseUrl.value
            selfTestMessage = null
            toast = "服务器地址已保存。请点击「连通性自检」重新建立会话。"
        }
    }

    val resetBaseUrl: () -> Unit = {
        container.settings.resetBaseUrl()
        baseUrlInput = container.settings.baseUrl.value
        effectiveUrl = container.settings.baseUrl.value
        selfTestMessage = null
        toast = "已恢复默认服务器地址。"
    }

    val runSelfTest: () -> Unit = {
        if (!selfTestRunning) {
            scope.launch {
                selfTestRunning = true
                selfTestMessage = null
                val startedAt = System.nanoTime()
                val sessionResult = repository.ensureSession(force = true)
                val sessionValue = sessionResult.getOrNull()
                if (sessionValue == null) {
                    val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                    selfTestTone = Tone.Danger
                    selfTestMessage = "自检失败：${friendlyError(sessionResult.exceptionOrNull())}（耗时 $elapsed ms）"
                } else {
                    session = sessionValue
                    val accountsResult = repository.accounts()
                    val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                    val accountList = accountsResult.getOrNull()
                    if (accountList == null) {
                        selfTestTone = Tone.Danger
                        selfTestMessage = "自检失败：会话已建立，但账户接口失败：" +
                            "${friendlyError(accountsResult.exceptionOrNull())}（耗时 $elapsed ms）"
                    } else {
                        selfTestTone = Tone.Success
                        selfTestMessage = "自检成功：会话已建立，账户接口正常" +
                            "（${accountList.size} 个账户），耗时 $elapsed ms。"
                    }
                }
                selfTestRunning = false
            }
        }
    }

    val refreshSession: () -> Unit = {
        if (!sessionBusy) {
            scope.launch {
                sessionBusy = true
                val result = repository.ensureSession(false)
                val value = result.getOrNull()
                if (value == null) {
                    toast = friendlyError(result.exceptionOrNull())
                } else {
                    session = value
                    toast = "会话信息已更新。"
                }
                sessionBusy = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DetailPageScaffold(navigator = navigator, title = "设置") {
            Text(
                text = "服务器地址、会话状态与连通性自检。所有数据均为本地合成数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            SectionCard(
                title = "服务器地址",
                subtitle = "保存后需要重新建立会话，新的地址才会生效。",
            ) {
                FormField(
                    label = "Base URL",
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    placeholder = BuildConfig.DEFAULT_BASE_URL,
                    keyboardType = KeyboardType.Uri,
                    singleLine = true,
                    hint = "默认 ${BuildConfig.DEFAULT_BASE_URL}；末尾斜杠会被自动去掉。",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryActionButton(
                        text = "保存地址",
                        onClick = saveBaseUrl,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryActionButton(
                        text = "恢复默认",
                        onClick = resetBaseUrl,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "当前生效地址：$effectiveUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "连通性自检",
                subtitle = "强制重建会话（GET /api/session），再请求 /api/accounts 验证数据链路。",
            ) {
                PrimaryActionButton(
                    text = "连通性自检",
                    onClick = runSelfTest,
                    loading = selfTestRunning,
                )
                val message = selfTestMessage
                if (message != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoBanner(text = message, tone = selfTestTone)
                }
            }

            SectionCard(title = "当前会话") {
                val current = session
                if (current == null) {
                    EmptyStateBlock(
                        title = "尚未建立会话",
                        hint = "点击上方「连通性自检」或「刷新会话信息」建立会话。",
                    )
                } else {
                    KeyValueRow("用户", current.user.ifBlank { "—" })
                    KeyValueRow("会话模式", modeLabel(current.mode))
                    KeyValueRow("模型", current.model.ifBlank { "—" })
                    KeyValueRow("认证方式", current.authentication.ifBlank { "—" })
                    KeyValueRow("CSRF Token", maskSecret(current.csrf))
                    KeyValueRow(
                        "故障注入",
                        if (current.faultsEnabled) "已启用（risk-demo）" else "未启用",
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryActionButton(
                    text = "刷新会话信息",
                    onClick = refreshSession,
                    loading = sessionBusy,
                )
            }

            SectionCard(title = "使用说明") {
                SettingsHintLine("默认服务器：${BuildConfig.DEFAULT_BASE_URL}")
                SettingsHintLine("Android 模拟器访问宿主机后端用 http://10.0.2.2:8080。")
                SettingsHintLine("真机调试改成电脑的局域网 IP（如 http://192.168.1.20:8080），并确保手机与电脑同一网段。")
                SettingsHintLine("修改地址后需要重新建立会话；会话 30 分钟无操作会失效，届时重新自检即可。")
                SettingsHintLine("本应用使用合成银行数据，仅用于本地演示；红色风险验证与模拟接口需要以 risk-demo profile 启动后端。")
            }

            Text(
                text = "提示：设置只保存在本机（SharedPreferences），不会上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
        PageToast(message = toast) { toast = null }
    }
}

@Composable
private fun SettingsHintLine(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
