package cn.safebank.app.ui.screens.savings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.MandateVo
import cn.safebank.app.data.model.RecipientVo
import cn.safebank.app.data.model.ScheduleVo
import cn.safebank.app.data.model.TaskPlanVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowVo
import cn.safebank.app.data.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 后端种子收款人；网络取不到时作为降级候选（id/名称与 api-contract §4.3 一致）。 */
internal val SAVINGS_FALLBACK_RECIPIENTS: List<RecipientVo> = listOf(
    RecipientVo(id = "travel-recipient", name = "我的旅游账户", note = "", label = "我的旅游账户"),
    RecipientVo(id = "zhang-a", name = "张三", note = "同学", label = "张三 3271"),
    RecipientVo(id = "zhang-b", name = "张三", note = "同事", label = "张三 8862"),
)

internal fun recipientOptionLabel(recipient: RecipientVo): String =
    if (recipient.note.isBlank()) recipient.name else "${recipient.name} · ${recipient.note}"

/**
 * 储蓄任务 tab 的页面状态：账户 / 收款人 / 已登记计划 / 联合任务列表，
 * 以及定时储蓄预览与联合任务创建等写操作的忙碌/错误状态。
 */
internal class SavingsStateHolder(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
) {

    var accounts by mutableStateOf<List<AccountVo>>(emptyList())
        private set
    var recipients by mutableStateOf(SAVINGS_FALLBACK_RECIPIENTS)
        private set
    var schedules by mutableStateOf<List<ScheduleVo>>(emptyList())
        private set
    var workflows by mutableStateOf<List<WorkflowVo>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set
    var schedulesLoading by mutableStateOf(false)
        private set
    var workflowsLoading by mutableStateOf(false)
        private set

    var accountsError by mutableStateOf<String?>(null)
        private set
    var schedulesError by mutableStateOf<String?>(null)
        private set
    var workflowsError by mutableStateOf<String?>(null)
        private set

    /** 表单校验/请求错误（中文）。 */
    var formError by mutableStateOf<String?>(null)
    /** 成功提示（中文）。 */
    var notice by mutableStateOf<String?>(null)

    /** 定时储蓄预览动作；非空时展示待确认操作卡。 */
    var previewAction by mutableStateOf<ActionVo?>(null)
        private set
    var previewBusy by mutableStateOf(false)
        private set

    /** QUERY_MANDATES 查询意图动作；非空时展示查询确认卡。 */
    var queryAction by mutableStateOf<ActionVo?>(null)
        private set
    var queryBusy by mutableStateOf(false)
        private set

    /** 查询回执返回的本人银行代扣列表。 */
    var mandates by mutableStateOf<List<MandateVo>>(emptyList())
        private set

    var createBusy by mutableStateOf(false)
        private set

    // ------------------------------------------------------------ 加载

    fun load() {
        scope.launch {
            loading = true
            accountsError = null
            val accountResult = repository.accounts()
            accountResult.onFailure { accountsError = failureText(it) }
            accountResult.getOrNull()?.let { accounts = it }
            loadRecipients()
            loading = false
            refreshSchedules()
            refreshWorkflows()
        }
    }

    fun retryAccounts() {
        scope.launch {
            accountsError = null
            val result = repository.accounts()
            result.onFailure { accountsError = failureText(it) }
            result.getOrNull()?.let { accounts = it }
            if (recipients === SAVINGS_FALLBACK_RECIPIENTS) loadRecipients()
        }
    }

    private suspend fun loadRecipients() {
        val merged = LinkedHashMap<String, RecipientVo>()
        for (query in listOf("travel-recipient", "张三")) {
            repository.recipients(query).getOrNull()?.forEach { merged[it.id] = it }
        }
        if (merged.isNotEmpty()) recipients = merged.values.toList()
    }

    fun refreshSchedules() {
        scope.launch {
            schedulesLoading = true
            schedulesError = null
            val result = repository.schedules()
            result.onFailure { schedulesError = failureText(it) }
            result.getOrNull()?.let { schedules = it }
            schedulesLoading = false
        }
    }

    fun refreshWorkflows() {
        scope.launch {
            workflowsLoading = true
            workflowsError = null
            val result = repository.workflows()
            result.onFailure { workflowsError = failureText(it) }
            result.getOrNull()?.let { workflows = it }
            workflowsLoading = false
        }
    }

    // ------------------------------------------------------------ 选项

    fun accountOptions(): List<Pair<String, String>> = accounts.map { account ->
        account.id to "${account.label} · ${Formatters.money(account.balanceMinor)}"
    }

    fun recipientOptions(): List<Pair<String, String>> = recipients.map { it.id to recipientOptionLabel(it) }

    fun recipientLabel(recipientId: String?): String {
        if (recipientId.isNullOrBlank()) return "—"
        return recipients.firstOrNull { it.id == recipientId }?.let { recipientOptionLabel(it) } ?: recipientId
    }

    fun accountLabel(accountId: String?): String {
        if (accountId.isNullOrBlank()) return "—"
        val account = accounts.firstOrNull { it.id == accountId }
        return account?.let { "${it.label}（${Formatters.money(it.balanceMinor)}）" } ?: accountId
    }

    fun hasMonthMandateSelection(): Boolean = mandates.any { it.status == "ACTIVE" && it.billingInterval == "MONTHLY" }

    // ------------------------------------------------------------ 定时储蓄

    fun previewSchedule(
        sourceId: String,
        recipientId: String,
        amountMinor: Long,
        firstDate: String,
        endDate: String,
        dayOfMonth: Int,
    ) {
        if (previewBusy) return
        previewBusy = true
        formError = null
        notice = null
        scope.launch {
            val result = repository.previewSchedule(
                sourceId = sourceId,
                recipientId = recipientId,
                amountMinor = amountMinor,
                firstDate = firstDate,
                endDate = endDate,
                dayOfMonth = dayOfMonth,
            )
            result.onFailure { formError = failureText(it) }
            result.getOrNull()?.let {
                previewAction = it
                notice = "已生成定时任务预览。请核对后勾选确认；创建计划不会立即扣款。"
            }
            previewBusy = false
        }
    }

    fun clearPreview() {
        previewAction = null
    }

    // ------------------------------------------------------------ 联合任务

    fun refreshMandates() {
        if (queryBusy) return
        queryBusy = true
        formError = null
        scope.launch {
            val result = repository.createQueryIntent()
            result.onFailure { formError = failureText(it) }
            result.getOrNull()?.let { queryAction = it }
            queryBusy = false
        }
    }

    /** 查询确认卡提交成功后：拿到代扣列表并收起确认卡。 */
    fun acceptQueryReceipt(result: ActionExecutionResultVo) {
        val data = result.data
        queryAction = null
        if (data != null) {
            mandates = data
            notice = "代扣查询已完成（共 ${data.size} 项）；只有有效且按月的代扣可以加入联合任务。"
        } else {
            formError = "查询回执中没有返回代扣数据（重复提交不会重复返回）。请重新发起查询。"
        }
    }

    fun clearQueryAction() {
        queryAction = null
    }

    fun createWorkflow(request: WorkflowRequestDto) {
        if (createBusy) return
        createBusy = true
        formError = null
        notice = null
        scope.launch {
            val result = repository.createWorkflow(request)
            result.onFailure { formError = failureText(it) }
            result.getOrNull()?.let { workflow ->
                notice = "联合任务已创建（版本 v${workflow.version} · ${Formatters.workflowStateLabel(workflow.state)}）。进入详情可逐项确认。"
                refreshWorkflows()
            }
            createBusy = false
        }
    }

    fun postNotice(text: String) {
        notice = text
        formError = null
    }

    fun postError(text: String) {
        formError = text
        notice = null
    }

    fun clearMessages() {
        notice = null
        formError = null
    }
}

/**
 * 联合任务详情页状态：任务、懒加载的计划节点、写入（推进/取消/改参）与动作查单结果。
 * 所有写操作都带当前 version；版本冲突（409 CONFLICT）会提示并自动刷新。
 */
internal class WorkflowDetailStateHolder(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
    private val workflowId: String,
) {

    var workflow by mutableStateOf<WorkflowVo?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** 改参对话框用的账户 / 收款人候选（懒加载一次）。 */
    var accounts by mutableStateOf<List<AccountVo>>(emptyList())
        private set
    var recipients by mutableStateOf(SAVINGS_FALLBACK_RECIPIENTS)
        private set

    var plan by mutableStateOf<TaskPlanVo?>(null)
        private set
    var planVisible by mutableStateOf(false)
        private set
    var planLoading by mutableStateOf(false)
        private set
    var planError by mutableStateOf<String?>(null)
        private set

    var writeBusy by mutableStateOf(false)
        private set

    var lookupActionId by mutableStateOf<String?>(null)
        private set
    var lookupLoading by mutableStateOf(false)
        private set
    var lookupResult by mutableStateOf<ActionExecutionResultVo?>(null)
        private set
    var lookupError by mutableStateOf<String?>(null)
        private set

    fun load() {
        scope.launch {
            loading = true
            val result = repository.workflow(workflowId)
            result.onFailure { error = failureText(it) }
            result.getOrNull()?.let {
                workflow = it
                error = null
            }
            loadLookups()
            loading = false
        }
    }

    private suspend fun loadLookups() {
        if (accounts.isEmpty()) {
            repository.accounts().getOrNull()?.let { accounts = it }
        }
        val merged = LinkedHashMap<String, RecipientVo>()
        for (query in listOf("travel-recipient", "张三")) {
            repository.recipients(query).getOrNull()?.forEach { merged[it.id] = it }
        }
        if (merged.isNotEmpty()) recipients = merged.values.toList()
    }

    fun accountOptions(): List<Pair<String, String>> = accounts.map { account ->
        account.id to "${account.label} · ${Formatters.money(account.balanceMinor)}"
    }

    fun recipientOptions(): List<Pair<String, String>> = recipients.map { it.id to recipientOptionLabel(it) }

    fun accountLabel(accountId: String?): String {
        if (accountId.isNullOrBlank()) return "—"
        val account = accounts.firstOrNull { it.id == accountId }
        return account?.let { "${it.label}（${Formatters.money(it.balanceMinor)}）" } ?: accountId
    }

    fun recipientLabel(recipientId: String?): String {
        if (recipientId.isNullOrBlank()) return "—"
        return recipients.firstOrNull { it.id == recipientId }?.let { recipientOptionLabel(it) } ?: recipientId
    }

    fun postNotice(text: String) {
        notice = text
    }

    fun refresh() {
        error = null
        load()
    }

    fun togglePlan() {
        planVisible = !planVisible
        if (planVisible && plan == null && !planLoading) loadPlan()
    }

    fun loadPlan() {
        if (planLoading) return
        planLoading = true
        planError = null
        scope.launch {
            val result = repository.workflowPlan(workflowId)
            result.onFailure { t ->
                planError = if (isVersionConflict(t)) {
                    "任务版本已变化，请刷新后查看。"
                } else {
                    "步骤读取失败：${failureText(t)}"
                }
            }
            result.getOrNull()?.let { plan = it }
            planLoading = false
        }
    }

    fun resume() = write { version ->
        repository.resumeWorkflow(workflowId, version)
    }

    fun cancelWorkflow() = write { version ->
        repository.cancelWorkflow(workflowId, version)
    }

    fun revise(request: WorkflowRequestDto) = write { version ->
        repository.reviseWorkflow(workflowId, version, request)
    }

    private fun write(operation: suspend (version: Int) -> Result<WorkflowVo>) {
        val current = workflow ?: return
        if (writeBusy) return
        writeBusy = true
        error = null
        notice = null
        scope.launch {
            val result = operation(current.version)
            result.onFailure { t ->
                if (isVersionConflict(t)) {
                    error = "任务版本已改变，请刷新后再操作（已自动刷新）。"
                    val refreshed = repository.workflow(workflowId)
                    refreshed.getOrNull()?.let { workflow = it }
                } else {
                    error = failureText(t)
                }
            }
            result.getOrNull()?.let { updated ->
                workflow = updated
                plan = null
                if (planVisible) loadPlan()
                notice = "操作成功（版本 v${updated.version} · ${Formatters.workflowStateLabel(updated.state)}）。"
            }
            writeBusy = false
        }
    }

    fun lookup(actionId: String) {
        lookupActionId = actionId
        lookupResult = null
        lookupError = null
        lookupLoading = true
        scope.launch {
            val result = repository.action(actionId)
            result.onFailure { lookupError = failureText(it) }
            result.getOrNull()?.let { lookupResult = it }
            lookupLoading = false
        }
    }

    fun closeLookup() {
        lookupActionId = null
        lookupResult = null
        lookupError = null
        lookupLoading = false
    }
}
