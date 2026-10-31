package cn.safebank.app.ui.screens.savings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.TaskPlanVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowStepVo
import cn.safebank.app.data.model.WorkflowVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar

/**
 * 联合任务详情：
 * - 顶部状态 / 预计节省 / partial 提示；
 * - 步骤时间线（中文 kind/status、savingMinor、errorCode、actionId 查单）；
 * - 计划视图懒加载；
 * - 待确认操作卡（授权 → 提交）；
 * - 查单并推进 / 取消 / 改参（全部带 version，409 冲突提示并自动刷新）。
 */
@Composable
fun WorkflowDetailRoute(navigator: AppNavigator, container: AppContainer, workflowId: String) {
    val scope = rememberCoroutineScope()
    val state = remember(workflowId) { WorkflowDetailStateHolder(scope, container.repository, workflowId) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showReviseDialog by remember { mutableStateOf(false) }

    LaunchedEffect(workflowId) { state.load() }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(navigator, "联合任务")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val workflow = state.workflow
            when {
                workflow == null && state.loading -> LoadingRow("正在读取任务详情…")
                workflow == null -> {
                    ErrorBanner(state.error ?: "任务读取失败，请重试。", onRetry = { state.refresh() })
                }
                else -> {
                    state.error?.let { message -> ErrorBanner(message, onRetry = { state.refresh() }) }
                    state.notice?.let { message -> Banner(message, BannerTone.SUCCESS) }

                    WorkflowHeaderSection(state, workflow)
                    WorkflowPendingActionSection(state, workflow, container.repository)
                    WorkflowStepsSection(state, workflow)
                    WorkflowPlanSection(state)
                    WorkflowControlsSection(
                        state = state,
                        workflow = workflow,
                        onCancel = { showCancelDialog = true },
                        onRevise = { showReviseDialog = true },
                    )
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("取消后续任务") },
            text = {
                Text(
                    "确定取消本任务所有未受理的后续步骤吗？\n" +
                        "已完成的银行动作不会回滚；取消只停止尚未受理的步骤。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    state.cancelWorkflow()
                }) {
                    Text("确认取消任务", color = SavingsPalette.BrandRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("返回") }
            },
        )
    }

    val reviseWorkflow = state.workflow
    if (showReviseDialog && reviseWorkflow != null) {
        ReviseWorkflowDialog(
            workflow = reviseWorkflow,
            state = state,
            onDismiss = { showReviseDialog = false },
            onSave = { request ->
                showReviseDialog = false
                state.revise(request)
            },
        )
    }

    ActionLookupDialog(state)
}

// ================================================================ 顶部摘要

@Composable
private fun WorkflowHeaderSection(state: WorkflowDetailStateHolder, workflow: WorkflowVo) {
    SectionPanel(title = "任务摘要") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "版本 v${workflow.version}",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = SavingsPalette.TextSecondary,
            )
            WorkflowStatusBadge(workflow.state)
        }
        Spacer(Modifier.height(8.dp))
        if (workflow.estimatedSavingMinor > 0) {
            KeyValueRow(
                "预计月度节省",
                Formatters.money(workflow.estimatedSavingMinor) + "（仅计本任务已核验撤销）",
            )
        } else {
            KeyValueRow("预计月度节省", "暂无已核验节省（仅计本任务已核验撤销）")
        }
        val request = workflow.request
        if (request != null) {
            KeyValueRow("账单月份", request.month)
            KeyValueRow("付款账户", state.accountLabel(request.sourceId))
            KeyValueRow("储蓄目标", state.recipientLabel(request.recipientId))
            KeyValueRow("首次日期", request.firstDate)
            KeyValueRow("结束日期", request.endDate)
            KeyValueRow("执行规则", "每月 ${request.dayOfMonth} 日 · Asia/Shanghai")
            KeyValueRow("明确选择的代扣", request.mandateIds.ifEmpty { listOf("—") }.joinToString("、"))
            KeyValueRow("仅计已确认节省", if (request.onlyConfirmedSavings) "是" else "否")
        }
        workflow.analysis?.let { analysis ->
            KeyValueRow(
                "账单快照",
                "${analysis.month} · 支出 ${Formatters.money(analysis.expenseMinor)} · 收入 ${Formatters.money(analysis.incomeMinor)}",
            )
        }
        workflowStateHint(workflow)?.let { hint ->
            Spacer(Modifier.height(8.dp))
            Banner(hint, BannerTone.WARNING)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { state.refresh() }, enabled = !state.loading) {
                Text("刷新详情")
            }
        }
    }
}

// ================================================================ 待确认操作

@Composable
private fun WorkflowPendingActionSection(
    state: WorkflowDetailStateHolder,
    workflow: WorkflowVo,
    repository: SafeBankRepository,
) {
    val pendingAction = workflow.pendingActionOrNull
    if (pendingAction != null) {
        Text(
            text = "待确认操作",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = SavingsPalette.TextPrimary,
        )
        ActionConfirmCard(
            action = pendingAction,
            repository = repository,
            onChanged = { changed ->
                if (changed.status == "CANCELLED") state.refresh()
            },
            onSubmitted = { result ->
                state.postNotice(receiptExplanation(result.kind, result.status, result.data != null))
                state.refresh()
            },
        )
        HintText("确认并提交后，请点击下方「查单并推进」核对步骤状态并生成下一步骤。")
    } else if (!isTerminalWorkflow(workflow.state)) {
        SectionPanel(title = "待确认操作") {
            HintText("当前没有待确认操作。点击下方「查单并推进」核对步骤状态并生成下一步骤。")
        }
    }
}

// ================================================================ 步骤时间线

@Composable
private fun WorkflowStepsSection(state: WorkflowDetailStateHolder, workflow: WorkflowVo) {
    SectionPanel(title = "操作记录与回执（${workflow.steps.size}）") {
        if (workflow.steps.isEmpty()) {
            HintText("暂无步骤记录。")
        }
        workflow.steps.forEachIndexed { index, step ->
            if (index > 0) HorizontalDivider(color = SavingsPalette.Line)
            WorkflowStepRow(step = step, state = state)
        }
    }
}

@Composable
private fun WorkflowStepRow(step: WorkflowStepVo, state: WorkflowDetailStateHolder) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stepLabel(step),
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SavingsPalette.TextPrimary,
            )
            ActionStatusBadge(step.status)
        }
        Spacer(Modifier.height(4.dp))
        val detail = buildString {
            append("版本 v").append(step.inputVersion)
            if (step.savingMinor > 0) append(" · 已核验节省 ").append(Formatters.money(step.savingMinor))
            errorCodeLabel(step.errorCode)?.let { append(" · 状态说明：").append(it) }
        }
        Text(text = detail, fontSize = 11.sp, color = SavingsPalette.TextSecondary)
        if (step.actionId.isNotBlank()) {
            TextButton(onClick = { state.lookup(step.actionId) }) {
                Text("查看动作", fontSize = 12.sp)
            }
        }
    }
}

private fun stepLabel(step: WorkflowStepVo): String = when (step.kind) {
    "REVOKE" -> "撤销 ${step.key.removePrefix("revoke:")}"
    "SCHEDULE" -> "登记月度储蓄"
    else -> Formatters.actionKindLabel(step.kind)
}

// ================================================================ 计划视图

@Composable
private fun WorkflowPlanSection(state: WorkflowDetailStateHolder) {
    SectionPanel(title = "完整计划与依赖") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "按顺序生成的计划节点（点击展开时懒加载）",
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = SavingsPalette.TextSecondary,
            )
            TextButton(onClick = { state.togglePlan() }) {
                Text(if (state.planVisible) "收起" else "查看")
            }
        }
        if (state.planVisible) {
            state.planError?.let { message ->
                ErrorBanner(message, onRetry = { state.loadPlan() })
            }
            if (state.planLoading) LoadingRow("正在读取原任务步骤…")
            val plan = state.plan
            if (plan != null) {
                PlanNodes(plan = plan, state = state)
                HintText("计划登记和前置校验不代表银行操作完成；定时委托登记也不代表未来到账。")
            } else if (!state.planLoading && state.planError == null) {
                HintText("尚未加载计划节点。")
            }
        }
    }
}

@Composable
private fun PlanNodes(plan: TaskPlanVo, state: WorkflowDetailStateHolder) {
    val indexById: Map<String, Int> = plan.nodes.mapIndexed { index, node -> node.id to index }.toMap()
    Text(
        text = "节省策略：${savingsPolicyLabel(plan.savingsPolicy)} · 分析账单 ${plan.analysisMonth}",
        fontSize = 11.sp,
        color = SavingsPalette.TextSecondary,
    )
    Spacer(Modifier.height(6.dp))
    plan.nodes.forEachIndexed { index, node ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = "步骤 ${index + 1}：${planNodeTypeLabel(node.type)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SavingsPalette.TextPrimary,
            )
            Text(
                text = "状态：${planNodeDisplayLabel(node.displayState)}",
                fontSize = 12.sp,
                color = SavingsPalette.TextSecondary,
            )
            if (node.dependsOn.isNotEmpty()) {
                val deps = node.dependsOn.joinToString("；") { dependencyId ->
                    val depIndex = indexById[dependencyId]
                    val depNode = plan.nodes.firstOrNull { it.id == dependencyId }
                    if (depIndex != null) {
                        "步骤 ${depIndex + 1}：${depNode?.let { planNodeTypeLabel(it.type) } ?: "业务步骤"}"
                    } else {
                        dependencyId
                    }
                }
                Text(text = "前置：$deps", fontSize = 11.sp, color = SavingsPalette.TextSecondary)
            }
            errorCodeLabel(node.errorCode)?.let {
                Text(text = "状态说明：$it", fontSize = 11.sp, color = SavingsPalette.FailedFg)
            }
            val nodeActionId = node.actionId
            if (!nodeActionId.isNullOrBlank()) {
                TextButton(onClick = { state.lookup(nodeActionId) }) {
                    Text("查看动作", fontSize = 12.sp)
                }
            }
        }
        if (index < plan.nodes.lastIndex) HorizontalDivider(color = SavingsPalette.Line)
    }
}

private fun savingsPolicyLabel(policy: String?): String = when (policy) {
    "verified_success_subset" -> "只统计本任务内已核验撤销的节省"
    null, "" -> "—"
    else -> policy
}

// ================================================================ 任务操作

@Composable
private fun WorkflowControlsSection(
    state: WorkflowDetailStateHolder,
    workflow: WorkflowVo,
    onCancel: () -> Unit,
    onRevise: () -> Unit,
) {
    val terminal = isTerminalWorkflow(workflow.state)
    SectionPanel(title = "任务操作") {
        if (!terminal) {
            HintText("取消或改参只作用于未受理的后续步骤；已完成的银行动作不会回滚。改参保存新版本后，原待确认项将失效。")
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { state.resume() }, enabled = !state.writeBusy) {
                Text(if (state.writeBusy) "处理中…" else "查单并推进")
            }
            if (workflow.pendingActionOrNull != null) {
                TextButton(onClick = {
                    state.postNotice("当前待确认操作卡已显示在本页「待确认操作」区域。")
                }) {
                    Text("查看当前确认项", fontSize = 12.sp)
                }
            }
        }
        if (!terminal) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel, enabled = !state.writeBusy) {
                    Text("取消后续任务", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onRevise, enabled = !state.writeBusy) {
                    Text("修改后续参数", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun isTerminalWorkflow(state: String?): Boolean = when (state) {
    "VERIFIED_SUCCESS", "PARTIAL_SUCCESS", "CANCELLED", "CANCEL_REQUESTED" -> true
    else -> false
}

// ================================================================ 改参对话框

@Composable
private fun ReviseWorkflowDialog(
    workflow: WorkflowVo,
    state: WorkflowDetailStateHolder,
    onDismiss: () -> Unit,
    onSave: (WorkflowRequestDto) -> Unit,
) {
    val request = workflow.request
    var month by remember { mutableStateOf(request?.month ?: "2026-09") }
    var sourceId by remember { mutableStateOf(request?.sourceId ?: "checking") }
    var recipientId by remember { mutableStateOf(request?.recipientId ?: "travel-recipient") }
    var firstDate by remember { mutableStateOf(request?.firstDate ?: "2026-11-20") }
    var endDate by remember { mutableStateOf(request?.endDate ?: "2027-10-20") }
    var error by remember { mutableStateOf<String?>(null) }

    val accountOptions = state.accountOptions().ifEmpty { listOf(sourceId to sourceId) }
    val recipientOptions = state.recipientOptions()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改后续参数") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Banner("原待确认项将失效；已完成的银行动作不受影响。", BannerTone.WARNING)
                Spacer(Modifier.height(10.dp))
                SavingsTextField(
                    value = month,
                    onValueChange = { month = it },
                    label = "账单月份",
                    placeholder = "2026-09",
                )
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "付款账户",
                    options = accountOptions,
                    selectedId = sourceId,
                    onSelect = { sourceId = it },
                )
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = "储蓄目标",
                    options = recipientOptions,
                    selectedId = recipientId,
                    onSelect = { recipientId = it },
                )
                Spacer(Modifier.height(8.dp))
                SavingsTextField(
                    value = firstDate,
                    onValueChange = { firstDate = it },
                    label = "首次日期",
                    placeholder = "2026-11-20",
                )
                Spacer(Modifier.height(8.dp))
                SavingsTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = "结束日期",
                    placeholder = "2027-10-20",
                )
                Spacer(Modifier.height(8.dp))
                HintText("代扣选择（${request?.mandateIds?.size ?: 0} 项）在改参中保持不变。")
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    ErrorBanner(it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mandateIds = request?.mandateIds ?: emptyList()
                val validation = validateWorkflowForm(
                    month = month,
                    firstDate = firstDate,
                    endDate = endDate,
                    sourceId = sourceId,
                    recipientId = recipientId,
                    mandateIds = mandateIds,
                )
                if (validation != null) {
                    error = validation
                } else {
                    val first = parseDate(firstDate)
                    if (first == null) {
                        error = "首次日期格式应为 YYYY-MM-DD。"
                    } else {
                        error = null
                        onSave(
                            WorkflowRequestDto(
                                month = month.trim(),
                                mandateIds = mandateIds,
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
            }) {
                Text("保存新版本")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ================================================================ 动作查单对话框

@Composable
private fun ActionLookupDialog(state: WorkflowDetailStateHolder) {
    val actionId = state.lookupActionId ?: return
    AlertDialog(
        onDismissRequest = { state.closeLookup() },
        title = { Text("动作查单") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                KeyValueRow("操作编号", actionId)
                if (state.lookupLoading) LoadingRow("正在查单…")
                state.lookupError?.let { message -> ErrorBanner(message) }
                val result = state.lookupResult
                if (result != null) {
                    LookupResultBlock(result)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.closeLookup() }) { Text("关闭") }
        },
    )
}

@Composable
private fun LookupResultBlock(result: ActionExecutionResultVo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = actionTitle(result.kind),
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SavingsPalette.TextPrimary,
        )
        ActionStatusBadge(result.status)
    }
    Spacer(Modifier.height(6.dp))
    KeyValueRow("回执编号", result.receiptId ?: "—")
    errorCodeLabel(result.errorCode)?.let { KeyValueRow("状态说明", it) }
    KeyValueRow("查单时间", Formatters.shortDateTime(result.observedAt ?: result.queriedAt))
    Text(
        text = receiptExplanation(result.kind, result.status, result.data != null),
        fontSize = 12.sp,
        color = SavingsPalette.TextSecondary,
    )
}
