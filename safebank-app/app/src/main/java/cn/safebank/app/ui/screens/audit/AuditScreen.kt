package cn.safebank.app.ui.screens.audit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.AuditEventVo
import cn.safebank.app.data.model.RiskSummaryVo
import cn.safebank.app.data.model.ScheduleVo
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.screens.more.DetailPageScaffold
import cn.safebank.app.ui.screens.more.DropdownField
import cn.safebank.app.ui.screens.more.EmptyStateBlock
import cn.safebank.app.ui.screens.more.FormField
import cn.safebank.app.ui.screens.more.InfoBanner
import cn.safebank.app.ui.screens.more.KeyValueRow
import cn.safebank.app.ui.screens.more.LoadingRow
import cn.safebank.app.ui.screens.more.PageToast
import cn.safebank.app.ui.screens.more.PrimaryActionButton
import cn.safebank.app.ui.screens.more.SecondaryActionButton
import cn.safebank.app.ui.screens.more.SectionCard
import cn.safebank.app.ui.screens.more.Tone
import cn.safebank.app.ui.screens.more.ToolkitDivider
import cn.safebank.app.ui.screens.more.auditEventLabel
import cn.safebank.app.ui.screens.more.friendlyError
import cn.safebank.app.ui.screens.more.moneyOrDash
import cn.safebank.app.ui.screens.more.shortId
import kotlinx.coroutines.launch

/**
 * 「实验与审计」二级页（ui-spec §4.6）：
 * 定时任务模拟验证（run-due，仅 risk-demo）+ 当日风险额度 + 审计事件列表 + 故障注入（faultsEnabled 时）。
 */
@Composable
fun AuditRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val repository = container.repository
    var toast by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    var session by remember { mutableStateOf<SessionVo?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var schedules by remember { mutableStateOf<List<ScheduleVo>>(emptyList()) }
    var schedulesError by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<AuditEventVo>>(emptyList()) }
    var auditError by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<RiskSummaryVo?>(null) }
    var summaryError by remember { mutableStateOf<String?>(null) }

    var runDate by remember { mutableStateOf(Formatters.today()) }
    var runBusy by remember { mutableStateOf(false) }

    var faultActionId by remember { mutableStateOf("") }
    var submitMode by remember { mutableStateOf("NONE") }
    var lookupMode by remember { mutableStateOf("NONE") }
    var faultBusy by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        loading = true
        sessionError = null
        schedulesError = null
        auditError = null
        summaryError = null
        repository.ensureSession(false)
            .onSuccess { session = it }
            .onFailure { error -> sessionError = friendlyError(error) }
        repository.schedules()
            .onSuccess { list -> schedules = list }
            .onFailure { error -> schedulesError = friendlyError(error) }
        repository.audit()
            .onSuccess { list -> events = list }
            .onFailure { error -> auditError = friendlyError(error) }
        repository.riskSummary()
            .onSuccess { result -> summary = result }
            .onFailure { error -> summaryError = friendlyError(error) }
        loading = false
    }

    val runDueAction: () -> Unit = {
        val date = runDate.trim()
        if (date.isEmpty()) {
            toast = "请输入模拟执行日期（YYYY-MM-DD）。"
        } else {
            scope.launch {
                runBusy = true
                repository.runDue(date)
                    .onSuccess {
                        toast = "已触发到期任务核对；请到「待确认操作与回执」查看结果与回执。"
                        refreshTick += 1
                    }
                    .onFailure { error -> toast = friendlyError(error) }
                runBusy = false
            }
        }
    }

    val applyFault: () -> Unit = {
        val actionId = faultActionId.trim()
        if (actionId.isEmpty()) {
            toast = "请输入要注入故障的动作编号（actionId）。"
        } else {
            scope.launch {
                faultBusy = true
                repository.injectFault(actionId, submitMode, lookupMode)
                    .onSuccess { toast = "该动作的实验故障已配置" }
                    .onFailure { error -> toast = friendlyError(error) }
                faultBusy = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DetailPageScaffold(navigator = navigator, title = "实验与审计") {
            Text(
                text = "查看合成银行记录与模拟验证结果。该页面仅用于本地实验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            val currentSessionError = sessionError
            if (currentSessionError != null) {
                InfoBanner(text = "会话信息读取失败：$currentSessionError", tone = Tone.Danger)
            }

            SectionCard(
                title = "定时任务模拟验证",
                subtitle = "在指定模拟日期触发已授权任务；重复触发同一期不会重复扣款。该入口仅用于本地实验。",
            ) {
                if (schedulesError != null) {
                    InfoBanner(text = schedulesError.orEmpty(), tone = Tone.Danger)
                } else if (schedules.isEmpty()) {
                    Text(
                        text = "暂无已登记的定时计划（可在「储蓄任务」页创建）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    schedules.forEach { schedule ->
                        Text(
                            text = "已登记：每月 ${schedule.dayOfMonth ?: "?"} 日 · " +
                                "${moneyOrDash(schedule.amountMinor)} · 首次 ${Formatters.shortDate(schedule.firstDate)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FormField(
                    label = "模拟执行日期",
                    value = runDate,
                    onValueChange = { runDate = it },
                    placeholder = "2026-11-20",
                    hint = "格式 YYYY-MM-DD；仅 risk-demo profile 支持模拟时钟。",
                )
                PrimaryActionButton(
                    text = "验证到期任务",
                    onClick = runDueAction,
                    loading = runBusy,
                )
            }

            SectionCard(title = "当日风险额度", subtitle = "业务日按 Asia/Shanghai 计算。") {
                val currentSummary = summary
                if (summaryError != null) {
                    InfoBanner(text = summaryError.orEmpty(), tone = Tone.Danger)
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(text = "重试", onClick = { refreshTick += 1 })
                } else if (currentSummary == null) {
                    LoadingRow("正在读取当日风险额度…")
                } else {
                    KeyValueRow("业务日", currentSummary.businessDate.ifBlank { "—" })
                    KeyValueRow("已提交占用", moneyOrDash(currentSummary.committedMinor))
                    KeyValueRow("已授权预留", moneyOrDash(currentSummary.reservedMinor))
                    KeyValueRow("未核验占用", moneyOrDash(currentSummary.unverifiedMinor))
                    KeyValueRow(
                        "安全锁定至",
                        currentSummary.lockedUntil?.let { Formatters.shortDateTime(it) } ?: "未锁定",
                    )
                    KeyValueRow(
                        "需要人工对账",
                        if (currentSummary.reconciliationRequired) "是（写操作可能被拒绝）" else "否",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(text = "刷新额度与审计", onClick = { refreshTick += 1 })
                }
            }

            SectionCard(title = "审计事件", subtitle = "按记录时间倒序，最多 50 条。") {
                if (auditError != null) {
                    InfoBanner(text = auditError.orEmpty(), tone = Tone.Danger)
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(text = "重试", onClick = { refreshTick += 1 })
                } else if (events.isEmpty()) {
                    if (loading) {
                        LoadingRow("正在读取审计事件…")
                    } else {
                        EmptyStateBlock(
                            title = "暂无审计事件",
                            hint = "银行操作、授权与模拟执行都会在此记录。",
                        )
                    }
                } else {
                    events.forEach { event ->
                        AuditEventRow(event)
                        ToolkitDivider()
                    }
                }
            }

            if (session?.faultsEnabled == true) {
                SectionCard(
                    title = "实验故障设置（仅当前动作）",
                    subtitle = "仅 risk-demo profile 且会话返回 faultsEnabled=true 时可用；配置只影响指定动作。",
                ) {
                    FormField(
                        label = "动作编号（actionId）",
                        value = faultActionId,
                        onValueChange = { faultActionId = it },
                        placeholder = "先预览一个动作，再填入完整操作编号",
                        hint = "需填写完整操作 UUID（36 位）；可查看待确认操作卡上的「操作编号」。",
                    )
                    DropdownField(
                        label = "提交故障",
                        options = listOf(
                            "NONE" to "正常",
                            "BEFORE_SUBMIT_FAILURE" to "银行明确拒绝",
                            "AFTER_COMMIT_RESPONSE_LOST" to "执行后丢失回执",
                            "PENDING_THEN_SUCCESS" to "先受理后完成",
                        ),
                        selectedKey = submitMode,
                        onSelect = { submitMode = it },
                    )
                    DropdownField(
                        label = "查单故障",
                        options = listOf(
                            "NONE" to "正常",
                            "LOOKUP_UNAVAILABLE" to "同时模拟查单不可用",
                        ),
                        selectedKey = lookupMode,
                        onSelect = { lookupMode = it },
                    )
                    PrimaryActionButton(
                        text = "应用故障设置",
                        onClick = applyFault,
                        loading = faultBusy,
                    )
                }
            } else {
                Text(
                    text = "故障注入面板未启用（当前会话 faultsEnabled=false）；" +
                        "需以 risk-demo profile 启动后端后重新建立会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
            }

            Text(
                text = "模拟与故障注入只影响本地演示数据，不会连接真实银行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
        PageToast(message = toast) { toast = null }
    }
}

@Composable
private fun AuditEventRow(event: AuditEventVo) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = auditEventLabel(event.event),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Formatters.shortDateTime(event.recordedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "操作 ${shortId(event.actionId)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
