package cn.safebank.app.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 后端 VO / DTO 的 Gson 映射。
 *
 * 命名规则（详见 docs/api-contract.md）：
 * - 请求体全部 camelCase → 直接使用 Kotlin 属性名；
 * - 响应体银行侧是 snake_case（balance_minor / payload_hash / user_id …），
 *   用 [SerializedName] 显式标注；
 * - workflow / 风险 / 对话侧是 camelCase，直接用属性名；
 * - 金额一律整数分（Long），日期为 "YYYY-MM-DD"/"YYYY-MM"，时间戳为 ISO-8601 字符串。
 *
 * 所有字段都给默认值，便于容忍后端省略字段（离线模式 / 不同 profile）。
 */

// ---------------------------------------------------------------- 会话

data class SessionVo(
    val user: String = "",
    val csrf: String = "",
    val mode: String = "",
    val model: String = "",
    val authentication: String = "",
    val faultsEnabled: Boolean = false,
)

// ---------------------------------------------------------------- 只读查询

data class AccountVo(
    val id: String = "",
    val label: String = "",
    @SerializedName("balance_minor") val balanceMinor: Long = 0L,
)

data class RecipientVo(
    val id: String = "",
    val name: String = "",
    val note: String = "",
    val label: String = "",
)

data class TransactionVo(
    val id: String = "",
    val merchant: String = "",
    val category: String = "",
    @SerializedName("amount_minor") val amountMinor: Long? = null,
    @SerializedName("booked_on") val bookedOn: String? = null,
)

data class StatementVo(
    val month: String = "",
    @SerializedName("expense_minor") val expenseMinor: Long = 0L,
    @SerializedName("income_minor") val incomeMinor: Long = 0L,
    @SerializedName("internal_transfer_minor") val internalTransferMinor: Long = 0L,
    val categories: Map<String, Long> = emptyMap(),
    val transactions: List<TransactionVo> = emptyList(),
    @SerializedName("anomaly_candidates") val anomalyCandidates: List<TransactionVo> = emptyList(),
    @SerializedName("anomaly_rule") val anomalyRule: String = "",
    @SerializedName("usage_data_available") val usageDataAvailable: Boolean = false,
)

data class RecurringPaymentVo(
    val merchant: String = "",
    val occurrences: Int = 0,
    @SerializedName("first_seen") val firstSeen: String? = null,
    @SerializedName("last_seen") val lastSeen: String? = null,
    @SerializedName("min_amount_minor") val minAmountMinor: Long = 0L,
    @SerializedName("max_amount_minor") val maxAmountMinor: Long = 0L,
    val classification: String = "",
    @SerializedName("usage_known") val usageKnown: Boolean = false,
)

data class MandateVo(
    val id: String = "",
    @SerializedName("user_id") val userId: String = "",
    val merchant: String = "",
    @SerializedName("amount_minor") val amountMinor: Long? = null,
    @SerializedName("renewal_date") val renewalDate: String? = null,
    val status: String = "",
    @SerializedName("contract_status") val contractStatus: String = "",
    @SerializedName("billing_interval") val billingInterval: String = "",
)

data class ScheduleVo(
    val id: String = "",
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("source_id") val sourceId: String = "",
    @SerializedName("recipient_id") val recipientId: String = "",
    @SerializedName("amount_minor") val amountMinor: Long? = null,
    @SerializedName("day_of_month") val dayOfMonth: Int? = null,
    @SerializedName("first_date") val firstDate: String? = null,
    @SerializedName("end_date") val endDate: String? = null,
    val status: String = "",
)

data class AuditEventVo(
    @SerializedName("action_id") val actionId: String = "",
    val event: String = "",
    @SerializedName("recorded_at") val recordedAt: String = "",
)

data class RiskSummaryVo(
    val businessDate: String = "",
    val committedMinor: Long = 0L,
    val reservedMinor: Long = 0L,
    val unverifiedMinor: Long = 0L,
    val lockedUntil: String? = null,
    val reconciliationRequired: Boolean = false,
)

// ---------------------------------------------------------------- 风险与验证

data class RiskDecisionVo(
    val decisionId: String? = null,
    val policyVersion: String = "",
    val riskLevel: String = "",
    val requiredAssurance: String = "",
    val reasonCode: String = "",
    val businessDate: String = "",
    val effectiveMinor: Long = 0L,
    val projectedMinor: Long = 0L,
    val validUntil: String = "",
    val nextStep: String = "",
    val verificationMode: String = "",
    val needsRefresh: Boolean = false,
) {
    val isRed: Boolean get() = riskLevel.equals("RED", ignoreCase = true)
    val isYellow: Boolean get() = riskLevel.equals("YELLOW", ignoreCase = true)
}

data class ChallengeVo(
    val challengeId: String = "",
    val actionId: String = "",
    val decisionId: String = "",
    val expiresAt: String = "",
    val status: String = "",
    val verificationMode: String = "",
)

data class VerificationProofVo(
    val verificationProofId: String = "",
    val actionId: String = "",
    val decisionId: String = "",
    val expiresAt: String = "",
    val verificationMode: String = "",
)

// ---------------------------------------------------------------- 动作（扁平）

/**
 * ActionVO：后端用 `@JsonUnwrapped` 把 parameters 摊平到顶层，
 * 因此这里把所有 kind 的字段放进同一个类，用 kind 判断哪些字段有意义。
 */
data class ActionVo(
    val id: String = "",
    @SerializedName("user_id") val userId: String = "",
    val kind: String = "",
    @SerializedName("payload_hash") val payloadHash: String = "",
    val status: String = "",
    @SerializedName("expires_at") val expiresAt: String = "",
    @SerializedName("receipt_id") val receiptId: String? = null,
    @SerializedName("error_code") val errorCode: String? = null,
    val security: RiskDecisionVo? = null,

    // TRANSFER / SCHEDULE
    @SerializedName("source_id") val sourceId: String? = null,
    @SerializedName("recipient_id") val recipientId: String? = null,
    @SerializedName("recipient_label") val recipientLabel: String? = null,
    @SerializedName("amount_minor") val amountMinor: Long? = null,
    val currency: String? = null,
    @SerializedName("fee_minor") val feeMinor: Long? = null,
    @SerializedName("schedule_id") val scheduleId: String? = null,
    @SerializedName("occurrence_date") val occurrenceDate: String? = null,

    // REVOKE
    @SerializedName("mandate_id") val mandateId: String? = null,
    val merchant: String? = null,
    @SerializedName("charge_amount_minor") val chargeAmountMinor: Long? = null,
    @SerializedName("billing_interval") val billingInterval: String? = null,
    @SerializedName("estimated_monthly_saving_minor") val estimatedMonthlySavingMinor: Long? = null,
    @SerializedName("contract_status") val contractStatus: String? = null,
    val scope: String? = null,

    // SCHEDULE
    @SerializedName("first_date") val firstDate: String? = null,
    @SerializedName("end_date") val endDate: String? = null,
    @SerializedName("day_of_month") val dayOfMonth: Int? = null,
    val timezone: String? = null,
    @SerializedName("insufficient_funds") val insufficientFunds: String? = null,

    // QUERY_MANDATES
    val query: String? = null,
) {
    /** 空对象 `{}`（workflow.pendingAction 的“无待确认项”形态）会被 Gson 解析成全空对象。 */
    fun isEmptyObject(): Boolean = id.isEmpty() && kind.isEmpty()

    val isAwaitingAuth: Boolean get() = status == "AWAITING_AUTH"
}

/**
 * 执行回执 / 查单结果：在 ActionVO 的基础上追加执行观测字段。
 */
data class ActionExecutionResultVo(
    val id: String = "",
    @SerializedName("user_id") val userId: String = "",
    val kind: String = "",
    @SerializedName("payload_hash") val payloadHash: String = "",
    val status: String = "",
    @SerializedName("expires_at") val expiresAt: String = "",
    @SerializedName("receipt_id") val receiptId: String? = null,
    @SerializedName("error_code") val errorCode: String? = null,
    val security: RiskDecisionVo? = null,

    @SerializedName("source_id") val sourceId: String? = null,
    @SerializedName("recipient_id") val recipientId: String? = null,
    @SerializedName("recipient_label") val recipientLabel: String? = null,
    @SerializedName("amount_minor") val amountMinor: Long? = null,
    val currency: String? = null,
    @SerializedName("fee_minor") val feeMinor: Long? = null,
    @SerializedName("schedule_id") val scheduleId: String? = null,
    @SerializedName("occurrence_date") val occurrenceDate: String? = null,

    @SerializedName("mandate_id") val mandateId: String? = null,
    val merchant: String? = null,
    @SerializedName("charge_amount_minor") val chargeAmountMinor: Long? = null,
    @SerializedName("billing_interval") val billingInterval: String? = null,
    @SerializedName("estimated_monthly_saving_minor") val estimatedMonthlySavingMinor: Long? = null,
    @SerializedName("contract_status") val contractStatus: String? = null,
    val scope: String? = null,

    @SerializedName("first_date") val firstDate: String? = null,
    @SerializedName("end_date") val endDate: String? = null,
    @SerializedName("day_of_month") val dayOfMonth: Int? = null,
    val timezone: String? = null,
    @SerializedName("insufficient_funds") val insufficientFunds: String? = null,

    val query: String? = null,

    // 执行观测（仅提交/查单后才出现）
    @SerializedName("observed_at") val observedAt: String? = null,
    val observation: String? = null,
    // QUERY_MANDATES 专用：只在 submit 那一次返回数组，查单返回 null
    val data: List<MandateVo>? = null,
    val queriedAt: String? = null,
) {
    fun toActionVo(): ActionVo = ActionVo(
        id = id, userId = userId, kind = kind, payloadHash = payloadHash, status = status,
        expiresAt = expiresAt, receiptId = receiptId, errorCode = errorCode, security = security,
        sourceId = sourceId, recipientId = recipientId, recipientLabel = recipientLabel,
        amountMinor = amountMinor, currency = currency, feeMinor = feeMinor,
        scheduleId = scheduleId, occurrenceDate = occurrenceDate,
        mandateId = mandateId, merchant = merchant, chargeAmountMinor = chargeAmountMinor,
        billingInterval = billingInterval,
        estimatedMonthlySavingMinor = estimatedMonthlySavingMinor,
        contractStatus = contractStatus, scope = scope,
        firstDate = firstDate, endDate = endDate, dayOfMonth = dayOfMonth,
        timezone = timezone, insufficientFunds = insufficientFunds, query = query,
    )
}

// ---------------------------------------------------------------- 联合任务

data class WorkflowRequestDto(
    val month: String = "",
    val mandateIds: List<String> = emptyList(),
    val sourceId: String = "",
    val recipientId: String = "",
    val firstDate: String = "",
    val endDate: String = "",
    val dayOfMonth: Int = 20,
    val onlyConfirmedSavings: Boolean = true,
)

data class WorkflowStepVo(
    val key: String = "",
    val kind: String = "",
    val status: String = "",
    val actionId: String = "",
    val inputVersion: Int = 1,
    val savingMinor: Long = 0L,
    val errorCode: String? = null,
)

data class WorkflowVo(
    val id: String = "",
    val version: Int = 1,
    val state: String = "",
    val request: WorkflowRequestDto? = null,
    val estimatedSavingMinor: Long = 0L,
    val partial: Boolean = false,
    val steps: List<WorkflowStepVo> = emptyList(),
    /** 无待确认项时后端返回 `{}`；用 [pendingActionOrNull] 判空。 */
    val pendingAction: ActionVo? = null,
    val analysis: StatementVo? = null,
) {
    val pendingActionOrNull: ActionVo?
        get() = pendingAction?.takeUnless { it.isEmptyObject() }
}

data class TaskPlanNodeVo(
    val id: String = "",
    val type: String = "",
    val dependsOn: List<String> = emptyList(),
    val displayState: String = "",
    val actionId: String? = null,
    val errorCode: String? = null,
)

data class TaskPlanVo(
    val workflowId: String = "",
    val version: Int = 1,
    val state: String = "",
    val savingsPolicy: String = "",
    val analysisMonth: String = "",
    val nodes: List<TaskPlanNodeVo> = emptyList(),
)

// ---------------------------------------------------------------- 对话上下文

data class ConversationDisplayTurn(
    val user: String = "",
    val assistant: String = "",
)

data class BankReference(
    val toolCallId: String = "",
    val kind: String = "",
    val bankObjectId: String = "",
)

data class ConversationVo(
    val conversationId: String = "",
    val version: Int = 0,
    val intent: String = "",
    val state: String = "",
    val slots: Map<String, JsonElement> = emptyMap(),
    val proposal: JsonElement? = null,
    val displayHistory: List<ConversationDisplayTurn> = emptyList(),
    val bankReferences: List<BankReference> = emptyList(),
)

/**
 * `/api/chat` 响应。动态/可选部分保留为 [JsonElement]，
 * 页面用辅助方法读取，避免为每种工具结果建一套强类型。
 */
data class ChatResponse(
    val text: String = "",
    val mode: String = "",
    val data: JsonElement? = null,
    val actions: List<ActionVo> = emptyList(),
    val trace: List<String> = emptyList(),
    val contextResults: List<JsonElement> = emptyList(),
    val modelUsage: JsonElement? = null,
    val timing: JsonElement? = null,
    /** 三种形态：null / {"required":bool} / {question,missingSlots,recipientCandidates} */
    val clarification: JsonElement? = null,
    val conversationId: String = "",
    val contextVersion: Int = 0,
    val context: ConversationVo? = null,
    // 仅模型链路才有（OFFLINE_DEMO 完全不带这些键）
    val results: List<JsonElement>? = null,
    val answerSource: String? = null,
    val factReferences: List<String>? = null,
    // 仅 agent-gateway / langgraph 模式
    val agentBackend: String? = null,
    val toolResults: List<JsonElement>? = null,
) {
    fun clarificationQuestion(): String? {
        val el = clarification ?: return null
        if (!el.isJsonObject) return null
        val obj = el.asJsonObject
        val q = obj.get("question")
        if (q != null && !q.isJsonNull) return q.asString
        return null
    }

    fun clarificationMissingSlots(): List<String> {
        val el = clarification ?: return emptyList()
        if (!el.isJsonObject) return emptyList()
        val arr = el.asJsonObject.get("missingSlots") ?: return emptyList()
        if (!arr.isJsonArray) return emptyList()
        return arr.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
    }

    val pendingActions: List<ActionVo>
        get() = actions.filter { it.isAwaitingAuth }
}

// ---------------------------------------------------------------- 请求体

data class TransferPreviewRequest(
    val sourceId: String,
    val recipientId: String,
    val amountMinor: Long,
)

data class MandatePreviewRequest(val mandateId: String)

data class SchedulePreviewRequest(
    val sourceId: String,
    val recipientId: String,
    val amountMinor: Long,
    val firstDate: String,
    val endDate: String,
    val dayOfMonth: Int,
)

data class QueryIntentRequest(
    val operation: String = "QUERY_MANDATES",
    val conversationId: String? = null,
    val expectedContextVersion: Int? = null,
)

data class AuthorizationRequest(
    val payloadHash: String,
    val decisionId: String? = null,
    val userConfirmed: Boolean = true,
    val verificationProofId: String? = null,
)

data class ChallengeRequest(
    val payloadHash: String? = null,
    val decisionId: String? = null,
)

data class ChallengeVerifyRequest(val code: String? = null)

data class WorkflowVersionRequest(val version: Int)

data class WorkflowRevisionRequest(
    val version: Int,
    val request: WorkflowRequestDto,
)

data class ContextResetRequest(val expectedContextVersion: Int)

data class ChatRequest(
    val text: String,
    val modelRequestLimit: Int? = null,
    val conversationId: String? = null,
    val requestId: String? = null,
    val expectedContextVersion: Int? = null,
)

data class SimulationFaultRequest(
    val actionId: String,
    val submitMode: String,
    val lookupMode: String = "NONE",
)

data class SimulationRunRequest(val date: String)

// ---------------------------------------------------------------- 金融推荐

/**
 * t020 后端：FinanceProduct / FinanceRecommendationVO 是 Java record，序列化为 **camelCase**，
 * 不使用旧银行侧模块的 snake_case，因此这些模型不再添加 @SerializedName。
 */
data class FinanceProductVo(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    val riskLevel: Int = 0,
    val riskLabel: String = "",
    val minimumAmountMinor: Long = 0L,
    val minimumHorizonMonths: Int = 0,
    val lockMonths: Int = 0,
    val redemptionBusinessDays: Int = 0,
    val capitalProtected: Boolean = false,
    val periodReturnBps: Int? = null,
    val maxDrawdownBps: Int? = null,
    val annualFeeBps: Int? = null,
    val liquidityNote: String = "",
    val description: String = "",
    val riskNote: String = "",
)

data class FinancePreferenceVo(
    val riskPreference: String = "CONSERVATIVE",
    val purpose: String = "RESERVE",
    val liquidity: String = "IMMEDIATE",
    val amountMinor: Long = 0L,
    val horizonMonths: Int = 0,
    val capitalProtection: Boolean = false,
)

data class FinanceCandidateVo(
    val product: FinanceProductVo = FinanceProductVo(),
    val reasons: List<String> = emptyList(),
)

data class FinanceExclusionVo(
    val productId: String = "",
    val productName: String = "",
    val reasons: List<String> = emptyList(),
)

data class FinanceRecommendationVo(
    val mode: String = "",
    val source: String = "",
    val catalogVersion: String = "",
    val asOf: String = "",
    val periodStart: String = "",
    val periodEnd: String = "",
    val generatedAt: String = "",
    val disclosure: String = "",
    val preferences: FinancePreferenceVo? = null,
    val evaluatedCount: Int = 0,
    val summary: String = "",
    val candidates: List<FinanceCandidateVo> = emptyList(),
    val exclusions: List<FinanceExclusionVo> = emptyList(),
    val rules: List<String> = emptyList(),
)

data class FinanceRecommendationRequest(
    val riskPreference: String,
    val purpose: String,
    val liquidity: String,
    val amountMinor: Long,
    val horizonMonths: Int,
    val capitalProtection: Boolean,
)

// ---------------------------------------------------------------- 卡片管理

/**
 * t020 后端 BankCardVO / CardOperationVO / CardChangeDTO 同样是裸 camelCase record，
 * 不使用 snake_case；请求与响应字段名一致，因此这里不再添加 @SerializedName。
 */
data class BankCardVo(
    val id: String = "",
    val accountId: String = "",
    val accountLabel: String = "",
    val balanceMinor: Long = 0L,
    val label: String = "",
    val cardType: String = "",
    val maskedNumber: String = "",
    val status: String = "",
    val perTransactionLimitMinor: Long = 0L,
    val dailyLimitMinor: Long = 0L,
    val onlineEnabled: Boolean = false,
    val overseasEnabled: Boolean = false,
    val version: Long = 0L,
)

data class CardChangeVo(
    val kind: String = "",
    val cardId: String? = null,
    val accountId: String? = null,
    val label: String? = null,
    val cardType: String? = null,
    val perTransactionLimitMinor: Long? = null,
    val dailyLimitMinor: Long? = null,
    val onlineEnabled: Boolean? = null,
    val overseasEnabled: Boolean? = null,
)

data class CardOperationVo(
    val id: String = "",
    val kind: String = "",
    val status: String = "",
    val payloadHash: String = "",
    val request: CardChangeVo? = null,
    val expectedVersion: Long = 0L,
    val createdAt: String = "",
    val expiresAt: String? = null,
    val executedAt: String? = null,
    val receiptId: String? = null,
    val card: BankCardVo? = null,
)

data class CardChangeRequest(
    val kind: String,
    val cardId: String? = null,
    val accountId: String? = null,
    val label: String? = null,
    val cardType: String? = null,
    val perTransactionLimitMinor: Long? = null,
    val dailyLimitMinor: Long? = null,
    val onlineEnabled: Boolean? = null,
    val overseasEnabled: Boolean? = null,
)

data class CardConfirmRequest(
    val payloadHash: String,
    val confirmed: Boolean = true,
)

// ---------------------------------------------------------------- 账单扩展（t020）

/** 年度账单：后端 YearStatementVO（字段用 snake_case 输出）。 */
data class YearStatementVo(
    val year: String = "",
    @SerializedName("expense_minor") val expenseMinor: Long = 0L,
    @SerializedName("income_minor") val incomeMinor: Long = 0L,
    @SerializedName("internal_transfer_minor") val internalTransferMinor: Long = 0L,
    val categories: Map<String, Long> = emptyMap(),
    val months: List<YearMonthRowVo> = emptyList(),
    @SerializedName("record_count") val recordCount: Int = 0,
    val coverage: YearCoverageVo? = null,
)

data class YearMonthRowVo(
    val month: String = "",
    @SerializedName("expense_minor") val expenseMinor: Long = 0L,
    @SerializedName("income_minor") val incomeMinor: Long = 0L,
    @SerializedName("internal_transfer_minor") val internalTransferMinor: Long = 0L,
    @SerializedName("transaction_count") val transactionCount: Int = 0,
)

data class YearCoverageVo(
    @SerializedName("business_date") val businessDate: String = "",
    @SerializedName("year_in_progress") val yearInProgress: Boolean = false,
    @SerializedName("first_booked_on") val firstBookedOn: String? = null,
    @SerializedName("last_booked_on") val lastBookedOn: String? = null,
    @SerializedName("beyond_business_date") val beyondBusinessDate: Boolean = false,
)

/** 单月组合筛选结果：后端 StatementFilterVO（snake_case 输出）。 */
data class StatementFilterVo(
    val month: String = "",
    val category: String = "",
    val direction: String = "",
    @SerializedName("direction_code") val directionCode: String = "",
    @SerializedName("min_amount_minor") val minAmountMinor: Long = 0L,
    @SerializedName("expense_minor") val expenseMinor: Long = 0L,
    @SerializedName("income_minor") val incomeMinor: Long = 0L,
    @SerializedName("matched_count") val matchedCount: Int = 0,
    @SerializedName("list_scope_note") val listScopeNote: String = "",
    val categories: Map<String, Long> = emptyMap(),
    val transactions: List<TransactionVo> = emptyList(),
)

// ---------------------------------------------------------------- 用户与联系人（t020）

data class ManagedUserVo(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val gender: String = "",
    val note: String = "",
    val accountId: String = "",
    val accountLabel: String = "",
    val balanceMinor: Long = 0L,
    val ownAccount: Boolean = false,
    val savingsTarget: Boolean = false,
)

data class UserProfileVo(
    val name: String = "",
    val phone: String = "",
    val gender: String = "",
)

data class ManagedUserRequest(
    val name: String,
    val phone: String = "",
    val gender: String = "UNKNOWN",
    val note: String = "",
    val savingsTarget: Boolean = false,
    val accountId: String? = null,
    val initialBalanceMinor: Long = 0L,
)

data class UserProfileRequest(
    val name: String,
    val phone: String = "",
    val gender: String = "UNKNOWN",
)

// ---------------------------------------------------------------- 模型设置（t020）

data class ModelProviderVo(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    val baseUrl: String = "",
    val models: List<String> = emptyList(),
)

data class ModelSettingsVo(
    val provider: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val apiKeyHint: String = "",
    val enabled: Boolean = false,
    val verified: Boolean = false,
    val verifiedAt: String? = null,
    val revision: String = "",
    val source: String = "",
)

data class ModelSettingsRequest(
    val provider: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String? = null,
    val enabled: Boolean? = null,
    val clearApiKey: Boolean = false,
)

// ---------------------------------------------------------------- 错误体

/** 普通业务错误 ErrorVO / 风险错误 RiskErrorVO 的并集（字段按需取用）。 */
data class ApiErrorBody(
    val code: String = "",
    val message: String = "",
    val actionId: String? = null,
    val security: RiskDecisionVo? = null,
    val retryAt: String? = null,
    val error: String? = null,
    val status: Int? = null,
    val path: String? = null,
)
