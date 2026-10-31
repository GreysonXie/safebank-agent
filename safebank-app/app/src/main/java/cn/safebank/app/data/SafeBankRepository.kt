package cn.safebank.app.data

import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.AuditEventVo
import cn.safebank.app.data.model.BankCardVo
import cn.safebank.app.data.model.CardConfirmRequest
import cn.safebank.app.data.model.CardOperationVo
import cn.safebank.app.data.model.ChallengeVo
import cn.safebank.app.data.model.ChatResponse
import cn.safebank.app.data.model.ConversationVo
import cn.safebank.app.data.model.FinanceRecommendationRequest
import cn.safebank.app.data.model.FinanceRecommendationVo
import cn.safebank.app.data.model.MandateVo
import cn.safebank.app.data.model.RecipientVo
import cn.safebank.app.data.model.RecurringPaymentVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.model.RiskSummaryVo
import cn.safebank.app.data.model.ScheduleVo
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.model.TaskPlanVo
import cn.safebank.app.data.model.VerificationProofVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowVo
import cn.safebank.app.data.model.YearStatementVo
import cn.safebank.app.data.model.StatementFilterVo
import cn.safebank.app.data.model.ManagedUserVo
import cn.safebank.app.data.model.ManagedUserRequest
import cn.safebank.app.data.model.UserProfileVo
import cn.safebank.app.data.model.UserProfileRequest
import cn.safebank.app.data.model.ModelProviderVo
import cn.safebank.app.data.model.ModelSettingsVo
import cn.safebank.app.data.model.ModelSettingsRequest
import com.google.gson.JsonElement

/**
 * 页面唯一的数据入口。所有方法都是 suspend + [Result]，
 * 失败统一为 [cn.safebank.app.data.network.ApiException]（带 business code / httpStatus）。
 *
 * 约定：
 * - 首次调用任意方法前仓库会自动建立会话（GET /api/session），并自动处理 401 重建会话；
 * - 所有非 GET 请求自动带 X-CSRF-Token；
 * - 金额参数一律整数分。
 */
interface SafeBankRepository {

    /** 建立/刷新会话，返回会话信息（含 csrf、模式、是否开启故障注入）。 */
    suspend fun ensureSession(force: Boolean = false): Result<SessionVo>

    /** 当前缓存会话（未建立时为 null），供设置页展示。 */
    fun currentSession(): SessionVo?

    // ------------------------------------------------------------ 只读查询

    suspend fun accounts(): Result<List<AccountVo>>

    suspend fun recipients(query: String): Result<List<RecipientVo>>

    suspend fun statement(month: String): Result<StatementVo>

    suspend fun recurring(): Result<List<RecurringPaymentVo>>

    suspend fun schedules(): Result<List<ScheduleVo>>

    suspend fun audit(): Result<List<AuditEventVo>>

    suspend fun riskSummary(): Result<RiskSummaryVo>

    // ------------------------------------------------------------ 动作预览

    suspend fun previewTransfer(sourceId: String, recipientId: String, amountMinor: Long): Result<ActionVo>

    suspend fun previewMandateRevoke(mandateId: String): Result<ActionVo>

    suspend fun previewSchedule(
        sourceId: String,
        recipientId: String,
        amountMinor: Long,
        firstDate: String,
        endDate: String,
        dayOfMonth: Int,
    ): Result<ActionVo>

    /** 生成 QUERY_MANDATES 动作（读代扣列表的唯一入口；GET /api/mandates 恒 409）。 */
    suspend fun createQueryIntent(
        conversationId: String? = null,
        expectedContextVersion: Int? = null,
    ): Result<ActionVo>

    // ------------------------------------------------------------ 动作生命周期

    suspend fun action(id: String): Result<ActionExecutionResultVo>

    suspend fun actionRisk(id: String): Result<RiskDecisionVo>

    /** 强制刷新风险判定（拿可授权 decisionId 的正确入口，needsRefresh 恒 false）。 */
    suspend fun refreshRiskPreview(id: String): Result<RiskDecisionVo>

    suspend fun createChallenge(id: String, payloadHash: String, decisionId: String): Result<ChallengeVo>

    suspend fun verifyChallenge(id: String, challengeId: String, code: String): Result<VerificationProofVo>

    suspend fun authorize(
        id: String,
        payloadHash: String,
        decisionId: String?,
        verificationProofId: String? = null,
        userConfirmed: Boolean = true,
    ): Result<ActionVo>

    suspend fun submit(id: String): Result<ActionExecutionResultVo>

    suspend fun cancel(id: String): Result<ActionVo>

    // ------------------------------------------------------------ 联合任务

    suspend fun workflows(): Result<List<WorkflowVo>>

    suspend fun workflow(id: String): Result<WorkflowVo>

    suspend fun workflowPlan(id: String): Result<TaskPlanVo>

    suspend fun createWorkflow(request: WorkflowRequestDto): Result<WorkflowVo>

    suspend fun resumeWorkflow(id: String, version: Int): Result<WorkflowVo>

    suspend fun reviseWorkflow(id: String, version: Int, request: WorkflowRequestDto): Result<WorkflowVo>

    suspend fun cancelWorkflow(id: String, version: Int): Result<WorkflowVo>

    // ------------------------------------------------------------ 对话

    suspend fun createConversation(): Result<ConversationVo>

    suspend fun conversation(id: String): Result<ConversationVo>

    suspend fun resetConversation(id: String, expectedContextVersion: Int): Result<ConversationVo>

    suspend fun chat(
        text: String,
        conversationId: String? = null,
        expectedContextVersion: Int? = null,
        requestId: String? = null,
        modelRequestLimit: Int? = null,
    ): Result<ChatResponse>

    // ------------------------------------------------------------ 模拟（仅 risk-demo profile）

    suspend fun injectFault(actionId: String, submitMode: String, lookupMode: String): Result<Unit>

    suspend fun runDue(date: String): Result<Unit>

    // ------------------------------------------------------------ 金融推荐

    suspend fun financeRecommend(request: FinanceRecommendationRequest): Result<FinanceRecommendationVo>

    // ------------------------------------------------------------ 卡片管理

    suspend fun cards(): Result<List<BankCardVo>>

    suspend fun cardOperations(): Result<List<CardOperationVo>>

    suspend fun previewCardChange(request: cn.safebank.app.data.model.CardChangeRequest): Result<CardOperationVo>

    suspend fun confirmCardOperation(id: String, payloadHash: String): Result<CardOperationVo>

    suspend fun cancelCardOperation(id: String): Result<CardOperationVo>

    // ------------------------------------------------------------ 账单扩展（t020）

    /** 年度账单；year 为空时后端用当前业务年份。 */
    suspend fun yearStatement(year: String? = null): Result<YearStatementVo>

    /** 单月组合筛选：category="全部"、direction="支出"/"收入"/"全部"、minAmountMinor 为单笔金额下限（分）。 */
    suspend fun statementFilter(
        month: String,
        category: String = "全部",
        direction: String = "支出",
        minAmountMinor: Long = 0L,
    ): Result<StatementFilterVo>

    suspend fun statementMonths(): Result<List<String>>

    suspend fun statementYears(): Result<List<String>>

    // ------------------------------------------------------------ 用户与联系人（t020）

    suspend fun users(): Result<List<ManagedUserVo>>

    suspend fun createUser(request: ManagedUserRequest): Result<ManagedUserVo>

    suspend fun updateUser(id: String, request: ManagedUserRequest): Result<ManagedUserVo>

    suspend fun userProfile(): Result<UserProfileVo>

    suspend fun saveUserProfile(request: UserProfileRequest): Result<UserProfileVo>

    // ------------------------------------------------------------ 模型设置（t020）

    suspend fun modelProviders(): Result<List<ModelProviderVo>>

    suspend fun modelConfiguration(): Result<ModelSettingsVo>

    suspend fun saveModelConfiguration(request: ModelSettingsRequest): Result<ModelSettingsVo>

    suspend fun disableModel(): Result<ModelSettingsVo>

    /** 真实调用一次模型供应商（POST /api/models/test），返回后端原始 JSON。 */
    suspend fun testModel(request: ModelSettingsRequest): Result<JsonElement>
}
