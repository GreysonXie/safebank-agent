package cn.safebank.app.data

import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.AuditEventVo
import cn.safebank.app.data.model.AuthorizationRequest
import cn.safebank.app.data.model.BankCardVo
import cn.safebank.app.data.model.CardChangeRequest
import cn.safebank.app.data.model.CardConfirmRequest
import cn.safebank.app.data.model.CardOperationVo
import cn.safebank.app.data.model.ChallengeRequest
import cn.safebank.app.data.model.ChallengeVerifyRequest
import cn.safebank.app.data.model.ChallengeVo
import cn.safebank.app.data.model.ChatRequest
import cn.safebank.app.data.model.ChatResponse
import cn.safebank.app.data.model.ContextResetRequest
import cn.safebank.app.data.model.ConversationVo
import cn.safebank.app.data.model.FinanceRecommendationRequest
import cn.safebank.app.data.model.FinanceRecommendationVo
import cn.safebank.app.data.model.MandatePreviewRequest
import cn.safebank.app.data.model.QueryIntentRequest
import cn.safebank.app.data.model.RecipientVo
import cn.safebank.app.data.model.RecurringPaymentVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.model.RiskSummaryVo
import cn.safebank.app.data.model.SchedulePreviewRequest
import cn.safebank.app.data.model.ScheduleVo
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.data.model.SimulationFaultRequest
import cn.safebank.app.data.model.SimulationRunRequest
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.model.TaskPlanVo
import cn.safebank.app.data.model.TransferPreviewRequest
import cn.safebank.app.data.model.VerificationProofVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowRevisionRequest
import cn.safebank.app.data.model.WorkflowVersionRequest
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
import cn.safebank.app.data.network.ApiException
import cn.safebank.app.data.network.ErrorParser
import cn.safebank.app.data.network.SafeBankHttp
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.CancellationException

/**
 * [SafeBankRepository] 的真实实现：OkHttp + 内存 CookieJar + CSRF 拦截器 + Gson。
 *
 * 全部端点见 docs/api-contract.md §4；页面用法见 docs/repository-contract.md。
 *
 * 约定：
 * - 每个方法都返回 `Result<T>`，**失败时一定是 [ApiException]**（网络错误也包装）；
 * - baseUrl 每次请求都从 [AppSettings.baseUrl] 重新读取（设置页改完立即生效，
 *   无需重建仓库）；若地址变化导致 `401 SESSION_REQUIRED`，数据层会自动重建会话并重放一次；
 * - 金额参数/返回值一律整数分。
 */
class AndroidBankRepository(
    private val settings: AppSettings,
) : SafeBankRepository {

    private val http = SafeBankHttp { settings.baseUrl.value }

    override fun currentSession(): SessionVo? = http.currentSession()

    override suspend fun ensureSession(force: Boolean): Result<SessionVo> = resultOf {
        http.ensureSession(force)
    }

    // ------------------------------------------------------------ 只读查询

    override suspend fun accounts(): Result<List<AccountVo>> = resultOf {
        http.get("/api/accounts", apiType<List<AccountVo>>())
    }

    override suspend fun recipients(query: String): Result<List<RecipientVo>> = resultOf {
        http.get("/api/recipients?query=${encode(query)}", apiType<List<RecipientVo>>())
    }

    override suspend fun statement(month: String): Result<StatementVo> = resultOf {
        val path = if (month.isBlank()) "/api/statement" else "/api/statement?month=${encode(month)}"
        http.get(path, apiType<StatementVo>())
    }

    override suspend fun recurring(): Result<List<RecurringPaymentVo>> = resultOf {
        http.get("/api/recurring", apiType<List<RecurringPaymentVo>>())
    }

    override suspend fun schedules(): Result<List<ScheduleVo>> = resultOf {
        http.get("/api/schedules", apiType<List<ScheduleVo>>())
    }

    override suspend fun audit(): Result<List<AuditEventVo>> = resultOf {
        http.get("/api/audit", apiType<List<AuditEventVo>>())
    }

    override suspend fun riskSummary(): Result<RiskSummaryVo> = resultOf {
        http.get("/api/risk/summary", apiType<RiskSummaryVo>())
    }

    // ------------------------------------------------------------ 动作预览

    override suspend fun previewTransfer(
        sourceId: String,
        recipientId: String,
        amountMinor: Long,
    ): Result<ActionVo> = resultOf {
        http.post(
            "/api/transfers/preview",
            TransferPreviewRequest(sourceId = sourceId, recipientId = recipientId, amountMinor = amountMinor),
            apiType<ActionVo>(),
        )
    }

    override suspend fun previewMandateRevoke(mandateId: String): Result<ActionVo> = resultOf {
        http.post(
            "/api/mandates/preview",
            MandatePreviewRequest(mandateId = mandateId),
            apiType<ActionVo>(),
        )
    }

    override suspend fun previewSchedule(
        sourceId: String,
        recipientId: String,
        amountMinor: Long,
        firstDate: String,
        endDate: String,
        dayOfMonth: Int,
    ): Result<ActionVo> = resultOf {
        http.post(
            "/api/schedules/preview",
            SchedulePreviewRequest(
                sourceId = sourceId,
                recipientId = recipientId,
                amountMinor = amountMinor,
                firstDate = firstDate,
                endDate = endDate,
                dayOfMonth = dayOfMonth,
            ),
            apiType<ActionVo>(),
        )
    }

    override suspend fun createQueryIntent(
        conversationId: String?,
        expectedContextVersion: Int?,
    ): Result<ActionVo> = resultOf {
        http.post(
            "/api/query-intents",
            QueryIntentRequest(
                operation = "QUERY_MANDATES",
                conversationId = conversationId,
                expectedContextVersion = expectedContextVersion,
            ),
            apiType<ActionVo>(),
        )
    }

    // ------------------------------------------------------------ 动作生命周期

    override suspend fun action(id: String): Result<ActionExecutionResultVo> = resultOf {
        http.get("/api/actions/${encode(id)}", apiType<ActionExecutionResultVo>())
    }

    override suspend fun actionRisk(id: String): Result<RiskDecisionVo> = resultOf {
        http.get("/api/actions/${encode(id)}/risk", apiType<RiskDecisionVo>())
    }

    override suspend fun refreshRiskPreview(id: String): Result<RiskDecisionVo> = resultOf {
        http.post("/api/actions/${encode(id)}/risk/preview", EMPTY_BODY, apiType<RiskDecisionVo>())
    }

    override suspend fun createChallenge(
        id: String,
        payloadHash: String,
        decisionId: String,
    ): Result<ChallengeVo> = resultOf {
        http.post(
            "/api/actions/${encode(id)}/verification-challenges",
            ChallengeRequest(payloadHash = payloadHash, decisionId = decisionId),
            apiType<ChallengeVo>(),
        )
    }

    override suspend fun verifyChallenge(
        id: String,
        challengeId: String,
        code: String,
    ): Result<VerificationProofVo> = resultOf {
        http.post(
            "/api/actions/${encode(id)}/verification-challenges/${encode(challengeId)}/verify",
            ChallengeVerifyRequest(code = code),
            apiType<VerificationProofVo>(),
        )
    }

    override suspend fun authorize(
        id: String,
        payloadHash: String,
        decisionId: String?,
        verificationProofId: String?,
        userConfirmed: Boolean,
    ): Result<ActionVo> = resultOf {
        http.post(
            "/api/actions/${encode(id)}/authorize",
            AuthorizationRequest(
                payloadHash = payloadHash,
                decisionId = decisionId,
                userConfirmed = userConfirmed,
                verificationProofId = verificationProofId,
            ),
            apiType<ActionVo>(),
        )
    }

    override suspend fun submit(id: String): Result<ActionExecutionResultVo> = resultOf {
        http.post("/api/actions/${encode(id)}/submit", EMPTY_BODY, apiType<ActionExecutionResultVo>())
    }

    override suspend fun cancel(id: String): Result<ActionVo> = resultOf {
        http.post("/api/actions/${encode(id)}/cancel", EMPTY_BODY, apiType<ActionVo>())
    }

    // ------------------------------------------------------------ 联合任务

    override suspend fun workflows(): Result<List<WorkflowVo>> = resultOf {
        http.get("/api/workflows", apiType<List<WorkflowVo>>())
    }

    override suspend fun workflow(id: String): Result<WorkflowVo> = resultOf {
        http.get("/api/workflows/${encode(id)}", apiType<WorkflowVo>())
    }

    override suspend fun workflowPlan(id: String): Result<TaskPlanVo> = resultOf {
        http.get("/api/workflows/${encode(id)}/plan", apiType<TaskPlanVo>())
    }

    override suspend fun createWorkflow(request: WorkflowRequestDto): Result<WorkflowVo> = resultOf {
        http.post("/api/workflows", request, apiType<WorkflowVo>())
    }

    override suspend fun resumeWorkflow(id: String, version: Int): Result<WorkflowVo> = resultOf {
        http.post(
            "/api/workflows/${encode(id)}/resume",
            WorkflowVersionRequest(version = version),
            apiType<WorkflowVo>(),
        )
    }

    override suspend fun reviseWorkflow(
        id: String,
        version: Int,
        request: WorkflowRequestDto,
    ): Result<WorkflowVo> = resultOf {
        http.post(
            "/api/workflows/${encode(id)}/revise",
            WorkflowRevisionRequest(version = version, request = request),
            apiType<WorkflowVo>(),
        )
    }

    override suspend fun cancelWorkflow(id: String, version: Int): Result<WorkflowVo> = resultOf {
        http.post(
            "/api/workflows/${encode(id)}/cancel",
            WorkflowVersionRequest(version = version),
            apiType<WorkflowVo>(),
        )
    }

    // ------------------------------------------------------------ 对话

    override suspend fun createConversation(): Result<ConversationVo> = resultOf {
        http.post("/api/conversations", EMPTY_BODY, apiType<ConversationVo>())
    }

    override suspend fun conversation(id: String): Result<ConversationVo> = resultOf {
        http.get("/api/conversations/${encode(id)}", apiType<ConversationVo>())
    }

    override suspend fun resetConversation(
        id: String,
        expectedContextVersion: Int,
    ): Result<ConversationVo> = resultOf {
        http.post(
            "/api/conversations/${encode(id)}/context/reset",
            ContextResetRequest(expectedContextVersion = expectedContextVersion),
            apiType<ConversationVo>(),
        )
    }

    override suspend fun chat(
        text: String,
        conversationId: String?,
        expectedContextVersion: Int?,
        requestId: String?,
        modelRequestLimit: Int?,
    ): Result<ChatResponse> = resultOf {
        http.post(
            "/api/chat",
            ChatRequest(
                text = text,
                modelRequestLimit = modelRequestLimit,
                conversationId = conversationId,
                requestId = requestId,
                expectedContextVersion = expectedContextVersion,
            ),
            apiType<ChatResponse>(),
        )
    }

    // ------------------------------------------------------------ 模拟（仅 risk-demo profile）

    override suspend fun injectFault(
        actionId: String,
        submitMode: String,
        lookupMode: String,
    ): Result<Unit> = resultOf {
        http.postUnit(
            "/api/simulation/faults",
            SimulationFaultRequest(actionId = actionId, submitMode = submitMode, lookupMode = lookupMode),
        )
    }

    override suspend fun runDue(date: String): Result<Unit> = resultOf {
        http.postUnit("/api/simulation/run-due", SimulationRunRequest(date = date))
    }

    // ------------------------------------------------------------ 金融推荐

    override suspend fun financeRecommend(request: FinanceRecommendationRequest): Result<FinanceRecommendationVo> = resultOf {
        http.post("/api/finance/recommendations", request, apiType<FinanceRecommendationVo>())
    }

    // ------------------------------------------------------------ 卡片管理

    override suspend fun cards(): Result<List<BankCardVo>> = resultOf {
        http.get("/api/cards", apiType<List<BankCardVo>>())
    }

    override suspend fun cardOperations(): Result<List<CardOperationVo>> = resultOf {
        http.get("/api/cards/operations", apiType<List<CardOperationVo>>())
    }

    override suspend fun previewCardChange(request: CardChangeRequest): Result<CardOperationVo> = resultOf {
        http.post("/api/cards/preview", request, apiType<CardOperationVo>())
    }

    override suspend fun confirmCardOperation(id: String, payloadHash: String): Result<CardOperationVo> = resultOf {
        http.post(
            "/api/cards/operations/${encode(id)}/confirm",
            CardConfirmRequest(payloadHash = payloadHash, confirmed = true),
            apiType<CardOperationVo>(),
        )
    }

    override suspend fun cancelCardOperation(id: String): Result<CardOperationVo> = resultOf {
        http.post("/api/cards/operations/${encode(id)}/cancel", EMPTY_BODY, apiType<CardOperationVo>())
    }

    // ------------------------------------------------------------ 账单扩展（t020）

    override suspend fun yearStatement(year: String?): Result<YearStatementVo> = resultOf {
        val path = if (year.isNullOrBlank()) "/api/statement/year" else "/api/statement/year?year=${encode(year)}"
        http.get(path, apiType<YearStatementVo>())
    }

    override suspend fun statementFilter(
        month: String,
        category: String,
        direction: String,
        minAmountMinor: Long,
    ): Result<StatementFilterVo> = resultOf {
        http.get(
            "/api/statement/filter?month=${encode(month)}&category=${encode(category)}" +
                "&direction=${encode(direction)}&minAmountMinor=$minAmountMinor",
            apiType<StatementFilterVo>(),
        )
    }

    override suspend fun statementMonths(): Result<List<String>> = resultOf {
        http.get("/api/statement/months", apiType<List<String>>())
    }

    override suspend fun statementYears(): Result<List<String>> = resultOf {
        http.get("/api/statement/years", apiType<List<String>>())
    }

    // ------------------------------------------------------------ 用户与联系人（t020）

    override suspend fun users(): Result<List<ManagedUserVo>> = resultOf {
        http.get("/api/users", apiType<List<ManagedUserVo>>())
    }

    override suspend fun createUser(request: ManagedUserRequest): Result<ManagedUserVo> = resultOf {
        http.post("/api/users", request, apiType<ManagedUserVo>())
    }

    override suspend fun updateUser(id: String, request: ManagedUserRequest): Result<ManagedUserVo> = resultOf {
        http.post("/api/users/${encode(id)}", request, apiType<ManagedUserVo>())
    }

    override suspend fun userProfile(): Result<UserProfileVo> = resultOf {
        http.get("/api/users/profile", apiType<UserProfileVo>())
    }

    override suspend fun saveUserProfile(request: UserProfileRequest): Result<UserProfileVo> = resultOf {
        http.post("/api/users/profile", request, apiType<UserProfileVo>())
    }

    // ------------------------------------------------------------ 模型设置（t020）

    override suspend fun modelProviders(): Result<List<ModelProviderVo>> = resultOf {
        http.get("/api/models/providers", apiType<List<ModelProviderVo>>())
    }

    override suspend fun modelConfiguration(): Result<ModelSettingsVo> = resultOf {
        http.get("/api/models/configuration", apiType<ModelSettingsVo>())
    }

    override suspend fun saveModelConfiguration(request: ModelSettingsRequest): Result<ModelSettingsVo> = resultOf {
        http.post("/api/models/configuration", request, apiType<ModelSettingsVo>())
    }

    override suspend fun disableModel(): Result<ModelSettingsVo> = resultOf {
        http.post("/api/models/disable", EMPTY_BODY, apiType<ModelSettingsVo>())
    }

    override suspend fun testModel(request: ModelSettingsRequest): Result<JsonElement> = resultOf {
        http.post("/api/models/test", request, apiType<JsonElement>())
    }

    // ------------------------------------------------------------ 内部工具

    /**
     * 统一把异常收敛成 `Result.failure(ApiException)`：
     * 协程取消必须继续抛出，否则会吞掉取消信号。
     */
    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(ErrorParser.network(e))
    } catch (e: Exception) {
        Result.failure(ErrorParser.unexpected(e))
    }

    private fun encode(value: String): String = try {
        java.net.URLEncoder.encode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }

    companion object {
        /** 后端接受 `{}` 作为"无请求体"（submit / cancel / risk preview 等）。 */
        private val EMPTY_BODY: Map<String, String> = emptyMap()
    }
}

/** `TypeToken` 简写：`apiType<List<AccountVo>>()`。 */
private inline fun <reified T> apiType(): Type = object : TypeToken<T>() {}.type
