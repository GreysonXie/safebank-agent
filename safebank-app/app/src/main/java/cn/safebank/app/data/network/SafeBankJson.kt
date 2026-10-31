package cn.safebank.app.data.network

import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.ApiErrorBody
import cn.safebank.app.data.model.AuditEventVo
import cn.safebank.app.data.model.BankCardVo
import cn.safebank.app.data.model.BankReference
import cn.safebank.app.data.model.CardChangeVo
import cn.safebank.app.data.model.CardOperationVo
import cn.safebank.app.data.model.FinanceCandidateVo
import cn.safebank.app.data.model.FinanceExclusionVo
import cn.safebank.app.data.model.FinancePreferenceVo
import cn.safebank.app.data.model.FinanceProductVo
import cn.safebank.app.data.model.FinanceRecommendationVo
import cn.safebank.app.data.model.ChallengeVo
import cn.safebank.app.data.model.ChatResponse
import cn.safebank.app.data.model.ConversationDisplayTurn
import cn.safebank.app.data.model.ConversationVo
import cn.safebank.app.data.model.MandateVo
import cn.safebank.app.data.model.RecurringPaymentVo
import cn.safebank.app.data.model.RecipientVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.model.RiskSummaryVo
import cn.safebank.app.data.model.ScheduleVo
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.model.TaskPlanNodeVo
import cn.safebank.app.data.model.TaskPlanVo
import cn.safebank.app.data.model.TransactionVo
import cn.safebank.app.data.model.VerificationProofVo
import cn.safebank.app.data.model.WorkflowRequestDto
import cn.safebank.app.data.model.WorkflowStepVo
import cn.safebank.app.data.model.WorkflowVo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import com.google.gson.JsonElement
import java.lang.reflect.Type

/**
 * 全 App 共用的 Gson 配置 + 少量反序列化后处理。
 *
 * 关键点（Gson 与 kotlinx.serialization 的差异）：
 * 1. **未知字段**：Gson 默认忽略，等价于 `ignoreUnknownKeys = true`（后端不同 profile 会多字段）。
 * 2. **缺省字段**：Gson 默认用 Unsafe 分配实例，不会走 Kotlin 构造器，因此 Models.kt 里的
 *    `= ""` / `= emptyList()` 默认值**不会自动生效**（非空字段会变成 null）。
 *    这里给每个响应 data class 注册一个"无参构造器 InstanceCreator"，让 Gson 先用
 *    Kotlin 生成的默认实例，再把 JSON 里出现的字段覆盖上去——
 *    于是缺省字段保留 **Kotlin 默认值**（这正是 `pendingAction: {}`、OFFLINE_DEMO
 *    `/api/chat` 缺 results/answerSource/factReferences 等容错所依赖的行为）。
 * 3. **请求体**：全部 camelCase，直接反射序列化；Gson 默认不输出 null 字段，
 *    所以可空请求字段（decisionId/verificationProofId/conversationId…）缺失即"不传"。
 * 4. **JSON null → Kotlin null**：`JsonElement?` 字段（chat.data / clarification / proposal…）
 *    在 Gson 里会得到 `JsonNull.INSTANCE` 而不是 null，页面 `== null` 判断会失效。
 *    [decode] 统一把 `JsonNull` 归一化成 null（Gson 不允许覆盖内置 JsonElement 适配器，
 *    所以只能放在解析后处理）。
 */
object SafeBankJson {

    /** 需要"缺省字段保留 Kotlin 默认值"的响应模型（全部构造函数参数都有默认值）。 */
    private val responseModels: List<Class<*>> = listOf(
        SessionVo::class.java,
        AccountVo::class.java,
        RecipientVo::class.java,
        TransactionVo::class.java,
        StatementVo::class.java,
        RecurringPaymentVo::class.java,
        MandateVo::class.java,
        ScheduleVo::class.java,
        AuditEventVo::class.java,
        RiskSummaryVo::class.java,
        RiskDecisionVo::class.java,
        ChallengeVo::class.java,
        VerificationProofVo::class.java,
        ActionVo::class.java,
        ActionExecutionResultVo::class.java,
        WorkflowRequestDto::class.java,
        WorkflowStepVo::class.java,
        WorkflowVo::class.java,
        TaskPlanNodeVo::class.java,
        TaskPlanVo::class.java,
        ConversationDisplayTurn::class.java,
        BankReference::class.java,
        ConversationVo::class.java,
        ChatResponse::class.java,
        ApiErrorBody::class.java,
        // 金融推荐
        FinanceProductVo::class.java,
        FinancePreferenceVo::class.java,
        FinanceCandidateVo::class.java,
        FinanceExclusionVo::class.java,
        FinanceRecommendationVo::class.java,
        // 卡片管理
        BankCardVo::class.java,
        CardChangeVo::class.java,
        CardOperationVo::class.java,
    )

    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .apply {
            for (type in responseModels) {
                registerDefaultInstance(this, type)
            }
        }
        .create()

    /**
     * 反序列化 + JsonNull 归一化。
     *
     * 用于所有响应体（不要直接用 [gson].fromJson，否则 `chat.clarification` /
     * `conversation.proposal` 这类字段在 JSON null 时会是 `JsonNull` 而不是 null）。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> decode(raw: String, type: Type): T =
        normalizeJsonNulls(gson.fromJson<T>(raw, type)) as T

    private fun registerDefaultInstance(builder: GsonBuilder, type: Class<*>) {
        val constructor = try {
            type.getDeclaredConstructor()
        } catch (e: NoSuchMethodException) {
            null
        } ?: return
        builder.registerTypeAdapter(type, InstanceCreator<Any> { constructor.newInstance() })
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> normalizeJsonNulls(value: T?): T? = when (value) {
        null -> null
        is JsonElement -> if (value.isJsonNull) null else value
        is ChatResponse -> value.copy(
            data = normalizeJsonNulls(value.data),
            modelUsage = normalizeJsonNulls(value.modelUsage),
            timing = normalizeJsonNulls(value.timing),
            clarification = normalizeJsonNulls(value.clarification),
            context = normalizeJsonNulls(value.context),
            contextResults = value.contextResults.filterNot { it.isJsonNull },
            results = value.results?.filterNot { it.isJsonNull },
            toolResults = value.toolResults?.filterNot { it.isJsonNull },
        ) as T
        is ConversationVo -> value.copy(
            proposal = normalizeJsonNulls(value.proposal),
        ) as T
        else -> value
    }
}
