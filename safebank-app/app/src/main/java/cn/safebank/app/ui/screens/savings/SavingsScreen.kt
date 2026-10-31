package cn.safebank.app.ui.screens.savings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.MandateVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.navigation.AppDestination
import cn.safebank.app.ui.navigation.AppNavigator

/**
 * 「储蓄任务」tab：定时储蓄（预览 → 授权 → 提交）+ 联合任务（创建 / 列表 / 详情入口）。
 * 详情页见 [WorkflowDetailRoute]。
 */
@Composable
fun SavingsRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val state = remember { SavingsStateHolder(scope, container.repository) }
    var tab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) { state.load() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SavingsPageHeader()

        if (state.loading) LoadingRow("正在建立会话并加载业务数据…")
        state.accountsError?.let { message ->
            ErrorBanner("账户加载失败：$message", onRetry = { state.retryAccounts() })
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = SavingsPalette.Surface,
        ) {
            Tab(
                selected = tab == 0,
                onClick = {
                    tab = 0
                    state.clearMessages()
                },
                text = { Text("定时储蓄") },
            )
            Tab(
                selected = tab == 1,
                onClick = {
                    tab = 1
                    state.clearMessages()
                },
                text = { Text("联合任务") },
            )
        }

        if (tab == 0) {
            SchedulePanel(state, container.repository)
        } else {
            WorkflowPanel(navigator, state, container.repository)
        }

        HintText("SafeBank v0.7 · 合成资金与身份 · 本地演示。所有银行操作都需逐项确认。")
    }
}

@Composable
private fun SavingsPageHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SavingsPalette.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SavingsPalette.Line, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "储蓄任务",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = SavingsPalette.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "把已确认的计划拆成可核验的步骤。",
            fontSize = 13.sp,
            color = SavingsPalette.TextSecondary,
        )
    }
}

// ================================================================ 定时储蓄

@Composable
private fun SchedulePanel(state: SavingsStateHolder, repository: SafeBankRepository) {
    var amountText by rememberSaveable { mutableStateOf("83") }
    var firstDate by rememberSaveable { mutableStateOf("2026-11-20") }
    var endDate by rememberSaveable { mutableStateOf("2027-10-20") }
    var sourceId by rememberSaveable { mutableStateOf("checking") }
    var recipientId by rememberSaveable { mutableStateOf("travel-recipient") }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.accounts) {
        val ids = state.accounts.map { it.id }
        if (ids.isNotEmpty() && sourceId !in ids) sourceId = ids.first()
    }
    LaunchedEffect(state.recipients) {
        val ids = state.recipients.map { it.id }
        if (ids.isNotEmpty() && recipientId !in ids) {
            recipientId = if ("travel-recipient" in ids) "travel-recipient" else ids.first()
        }
    }

    SectionPanel(
        title = "定时储蓄",
        subtitle = "生成预览并逐项确认后才会登记计划；创建计划不会立即扣款。",
    ) {
        DropdownField(
            label = "来源账户",
            options = state.accountOptions(),
            selectedId = sourceId,
            onSelect = { sourceId = it },
            enabled = !state.previewBusy,
        )
        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "储蓄目标",
            options = state.recipientOptions(),
            selectedId = recipientId,
            onSelect = { recipientId = it },
            enabled = !state.previewBusy,
        )
        Spacer(Modifier.height(10.dp))
        SavingsTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = "每月转入金额（元）",
            placeholder = "83.00",
            helper = "金额最多两位小数，必须为正数；演示单笔限额 ¥10,000。",
            isError = localError != null,
            keyboardType = KeyboardType.Decimal,
            enabled = !state.previewBusy,
        )
        Spacer(Modifier.height(10.dp))
        SavingsTextField(
            value = firstDate,
            onValueChange = { firstDate = it },
            label = "首次日期",
            placeholder = "2026-11-20",
            helper = "格式 YYYY-MM-DD；日必须在 1—28 日之间。",
            isError = localError != null,
            enabled = !state.previewBusy,
        )
        Spacer(Modifier.height(10.dp))
        SavingsTextField(
            value = endDate,
            onValueChange = { endDate = it },
            label = "结束日期",
            placeholder = "2027-10-20",
            helper = "不得早于首次日期，且最长一年。",
            isError = localError != null,
            enabled = !state.previewBusy,
        )
        Spacer(Modifier.height(8.dp))
        HintText("每月固定日 1—28 日，最长一年。创建任务不会立即扣款。")
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val error = validateScheduleForm(amountText, firstDate, endDate)
                if (error != null) {
                    localError = error
                } else {
                    val amountMinor = Formatters.parseAmountToMinor(amountText)
                    val first = parseDate(firstDate)
                    if (amountMinor == null || first == null) {
                        localError = "金额或日期无法解析，请检查输入。"
                    } else {
                        localError = null
                        state.previewSchedule(
                            sourceId = sourceId,
                            recipientId = recipientId,
                            amountMinor = amountMinor,
                            firstDate = firstDate.trim(),
                            endDate = endDate.trim(),
                            dayOfMonth = first.dayOfMonth,
                        )
                    }
                }
            },
            enabled = !state.previewBusy && !state.loading && state.accounts.isNotEmpty(),
        ) {
            Text(if (state.previewBusy) "正在生成预览…" else "生成定时任务预览")
        }

        if (state.accounts.isEmpty() && !state.loading) {
            Spacer(Modifier.height(8.dp))
            HintText("尚未加载到来源账户；请确认后端已启动，并在「设置」核对服务器地址。")
        }

        localError?.let { message ->
            Spacer(Modifier.height(10.dp))
            ErrorBanner("$message（未发送请求）")
        }
        state.formError?.let { message ->
            Spacer(Modifier.height(10.dp))
            ErrorBanner(message)
        }
        state.notice?.let { message ->
            Spacer(Modifier.height(10.dp))
            Banner(message, BannerTone.SUCCESS)
        }
    }

    // 定时储蓄预览确认卡
    state.previewAction?.let { preview ->
        ActionConfirmCard(
            action = preview,
            repository = repository,
            onSubmitted = { result ->
                state.refreshSchedules()
                state.postNotice(receiptExplanation(result.kind, result.status, result.data != null))
            },
            onDismiss = { state.clearPreview() },
        )
    }

    // 已登记计划
    SectionPanel(
        title = "已登记计划",
        subtitle = "来自 GET /api/schedules；登记计划不代表未来一定会转账。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "计划列表",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = SavingsPalette.TextPrimary,
            )
            TextButton(onClick = { state.refreshSchedules() }, enabled = !state.schedulesLoading) {
                Text(if (state.schedulesLoading) "加载中…" else "刷新")
            }
        }
        state.schedulesError?.let { message ->
            ErrorBanner(message, onRetry = { state.refreshSchedules() })
        }
        if (!state.schedulesLoading && state.schedulesError == null && state.schedules.isEmpty()) {
            HintText("还没有已登记的定时计划。完成预览并确认提交后，计划会出现在这里。")
        }
        state.schedules.forEach { schedule ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scheduleSummary(schedule),
                        fontSize = 13.sp,
                        color = SavingsPalette.TextPrimary,
                    )
                    Text(
                        text = "状态：${scheduleStatusLabel(schedule.status)} · 结束于 ${schedule.endDate ?: "—"}",
                        fontSize = 11.sp,
                        color = SavingsPalette.TextSecondary,
                    )
                }
            }
        }
    }
}

// ================================================================ 联合任务

@Composable
private fun WorkflowPanel(
    navigator: AppNavigator,
    state: SavingsStateHolder,
    repository: SafeBankRepository,
) {
    var month by rememberSaveable { mutableStateOf("2026-09") }
    var firstDate by rememberSaveable { mutableStateOf("2026-11-20") }
    var endDate by rememberSaveable { mutableStateOf("2027-10-20") }
    var sourceId by rememberSaveable { mutableStateOf("checking") }
    var recipientId by rememberSaveable { mutableStateOf("travel-recipient") }
    var selectedMandateIds by remember(state.mandates) { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(state.accounts) {
        val ids = state.accounts.map { it.id }
        if (ids.isNotEmpty() && sourceId !in ids) sourceId = ids.first()
    }
    LaunchedEffect(state.recipients) {
        val ids = state.recipients.map { it.id }
        if (ids.isNotEmpty() && recipientId !in ids) {
            recipientId = if ("travel-recipient" in ids) "travel-recipient" else ids.first()
        }
    }

    SectionPanel(
        title = "从账单到储蓄的联合任务",
        subtitle = "逐项确认 · 可恢复 · 有版本记录",
    ) {
        HintText(
            "选择你决定停用的月度代扣。系统先分析账单，再逐项撤销银行侧授权，" +
                "最后用已核验撤销对应的预计月度节省生成储蓄计划。" +
                "节省额不是余额增加，也不代表商户合同终止。",
        )
        Spacer(Modifier.height(12.dp))

        SavingsTextField(
            value = month,
            onValueChange = { month = it },
            label = "账单月份",
            placeholder = "2026-09",
            helper = "格式 YYYY-MM，例如 2026-09。",
            enabled = !state.createBusy,
        )
        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "付款账户",
            options = state.accountOptions(),
            selectedId = sourceId,
            onSelect = { sourceId = it },
            enabled = !state.createBusy,
        )
        Spacer(Modifier.height(10.dp))
        DropdownField(
            label = "储蓄目标",
            options = state.recipientOptions(),
            selectedId = recipientId,
            onSelect = { recipientId = it },
            enabled = !state.createBusy,
        )
        Spacer(Modifier.height(10.dp))
        SavingsTextField(
            value = firstDate,
            onValueChange = { firstDate = it },
            label = "首次日期",
            placeholder = "2026-11-20",
            helper = "必须是今天或未来；日必须在 1—28 日之间。",
            enabled = !state.createBusy,
        )
        Spacer(Modifier.height(10.dp))
        SavingsTextField(
            value = endDate,
            onValueChange = { endDate = it },
            label = "结束日期",
            placeholder = "2027-10-20",
            helper = "不得早于首次日期，且最长一年。",
            enabled = !state.createBusy,
        )
        Spacer(Modifier.height(12.dp))

        // 代扣多选：必须主动查询（QUERY_MANDATES 动作），不能直接 GET /api/mandates
        Text(
            text = "明确选择代扣",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SavingsPalette.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        HintText("读取本人银行代扣列表前需要先确认查询范围；查询本身不会转账或记账。")
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { state.refreshMandates() },
            enabled = !state.queryBusy && state.queryAction == null,
        ) {
            Text(if (state.queryBusy) "正在生成查询…" else "查询本人代扣")
        }

        state.queryAction?.let { queryAction ->
            Spacer(Modifier.height(10.dp))
            ActionConfirmCard(
                action = queryAction,
                repository = repository,
                onSubmitted = { result -> state.acceptQueryReceipt(result) },
                onDismiss = { state.clearQueryAction() },
            )
        }

        if (state.mandates.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            state.mandates.forEach { mandate ->
                MandateChoiceRow(
                    mandate = mandate,
                    checked = mandate.id in selectedMandateIds,
                    onToggle = { checked ->
                        selectedMandateIds = if (checked) {
                            selectedMandateIds + mandate.id
                        } else {
                            selectedMandateIds - mandate.id
                        }
                    },
                )
            }
            HintText("只有「银行侧授权有效」且按月的代扣可以加入联合任务；其余标记为不可新增选择。")
        } else if (state.queryAction == null) {
            Spacer(Modifier.height(8.dp))
            HintText("尚未查询代扣。请点击上方「查询本人代扣」并在确认卡上确认查询范围。")
        }

        if (state.mandates.isNotEmpty() && !state.hasMonthMandateSelection()) {
            Spacer(Modifier.height(6.dp))
            Banner("本次查询没有可用于联合任务的月度有效代扣，暂时无法创建任务。", BannerTone.WARNING)
        }

        Spacer(Modifier.height(10.dp))
        state.formError?.let { message ->
            ErrorBanner(message)
            Spacer(Modifier.height(8.dp))
        }
        state.notice?.let { message ->
            Banner(message, BannerTone.SUCCESS)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                val error = validateWorkflowForm(
                    month = month,
                    firstDate = firstDate,
                    endDate = endDate,
                    sourceId = sourceId,
                    recipientId = recipientId,
                    mandateIds = selectedMandateIds.toList(),
                )
                if (error != null) {
                    state.postError(error)
                } else {
                    val first = parseDate(firstDate)
                    if (first == null) {
                        state.postError("首次日期格式应为 YYYY-MM-DD。")
                    } else {
                        state.createWorkflow(
                            WorkflowRequestDto(
                                month = month.trim(),
                                mandateIds = selectedMandateIds.toList(),
                                sourceId = sourceId,
                                recipientId = recipientId,
                                firstDate = firstDate.trim(),
                                endDate = endDate.trim(),
                                dayOfMonth = first.dayOfMonth,
                                onlyConfirmedSavings = true,
                            ),
                        )
                    }
                }
            },
            enabled = !state.createBusy && state.accounts.isNotEmpty() && !state.loading,
        ) {
            Text(if (state.createBusy) "正在创建…" else "创建联合任务")
        }
    }

    // 任务列表：为空时按 ui-spec §4.4 不显示占位，只保留表单
    if (state.workflows.isNotEmpty() || state.workflowsError != null || state.workflowsLoading) {
        SectionPanel(title = "联合任务列表", subtitle = "点击查看步骤、计划与待确认操作") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "共 ${state.workflows.size} 个任务",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = SavingsPalette.TextSecondary,
                )
                TextButton(onClick = { state.refreshWorkflows() }, enabled = !state.workflowsLoading) {
                    Text(if (state.workflowsLoading) "加载中…" else "刷新")
                }
            }
            state.workflowsError?.let { message ->
                ErrorBanner(message, onRetry = { state.refreshWorkflows() })
            }
            state.workflows.forEach { workflow ->
                Spacer(Modifier.height(8.dp))
                WorkflowCard(
                    workflow = workflow,
                    state = state,
                    onClick = { navigator.push(AppDestination.WorkflowDetail(workflow.id)) },
                )
            }
        }
    }
}

@Composable
private fun MandateChoiceRow(mandate: MandateVo, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val selectable = mandate.status == "ACTIVE" && mandate.billingInterval == "MONTHLY"
    val amount = mandate.amountMinor?.let { Formatters.money(it) } ?: "金额未知"
    val unit = when (mandate.billingInterval) {
        "MONTHLY" -> "/月"
        "QUARTERLY" -> "/季度"
        "YEARLY" -> "/年"
        else -> ""
    }
    val checkboxHandler: ((Boolean) -> Unit)? = if (selectable) ({ value -> onToggle(value) }) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable) { onToggle(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = checkboxHandler,
            enabled = selectable,
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${mandate.merchant} · $amount$unit${if (selectable) "" else " · 不可新增选择"}",
                fontSize = 13.sp,
                color = if (selectable) SavingsPalette.TextPrimary else SavingsPalette.TextSecondary,
            )
            Text(
                text = "${mandateStatusLabel(mandate.status)} · ${Formatters.billingIntervalLabel(mandate.billingInterval)}" +
                    " · 合同状态未知" +
                    (mandate.renewalDate?.let { " · 续费日 $it" } ?: ""),
                fontSize = 11.sp,
                color = SavingsPalette.TextSecondary,
            )
        }
    }
}

@Composable
private fun WorkflowCard(workflow: WorkflowVo, state: SavingsStateHolder, onClick: () -> Unit) {
    val request = workflow.request
    val doneSteps = workflow.steps.count { it.status == "VERIFIED_SUCCESS" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SavingsPalette.SoftBlue, RoundedCornerShape(8.dp))
            .border(1.dp, SavingsPalette.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "联合任务 · 版本 ${workflow.version}",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SavingsPalette.TextPrimary,
            )
            WorkflowStatusBadge(workflow.state)
        }
        Spacer(Modifier.height(6.dp))
        if (request != null) {
            Text(
                text = "账单 ${request.month} → ${state.recipientLabel(request.recipientId)}",
                fontSize = 12.sp,
                color = SavingsPalette.TextSecondary,
            )
        }
        if (workflow.estimatedSavingMinor > 0) {
            Text(
                text = "预计月度节省 ${Formatters.money(workflow.estimatedSavingMinor)}（仅计本任务已核验撤销）",
                fontSize = 12.sp,
                color = SavingsPalette.DeepBlue,
            )
        } else {
            Text(
                text = "暂无已核验节省（仅计本任务已核验撤销）",
                fontSize = 12.sp,
                color = SavingsPalette.TextSecondary,
            )
        }
        Text(
            text = "步骤进度：$doneSteps/${workflow.steps.size} 已完成" +
                if (workflow.pendingActionOrNull != null) " · 有待确认操作" else "",
            fontSize = 12.sp,
            color = SavingsPalette.TextSecondary,
        )
        if (workflow.partial) {
            Text(
                text = "部分完成：已完成的银行操作不会因取消或修改后续任务而回滚。",
                fontSize = 11.sp,
                color = SavingsPalette.AwaitingFg,
            )
        }
    }
}

