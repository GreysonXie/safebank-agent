package cn.safebank.app.ui.screens.chat

import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.util.Formatters
import com.google.gson.Gson
import com.google.gson.JsonElement

/**
 * 对话页的文案 / 枚举映射（ui-design-spec §3.8、§3.12、§5.1、§5.2）。
 * 中文文案按 ui-spec 原文内联，便于对照验收。
 */

internal const val WELCOME_TEXT =
    "欢迎进入 SafeBank。账单可以直接提问；查看代扣请先确认查询范围，再分析收费周期。银行操作须核对预览并逐项确认。"

internal const val MAX_INPUT_CHARS = 4000

internal const val STATUS_THINKING = "正在处理问题并核对数据，请稍候…"
internal const val STATUS_LONG_WAIT = "正在理解问题并核对业务数据，等待原请求结果…"
internal const val STATUS_BUSY_BLOCK =
    "上次请求尚未完成，请先核对原请求。新问题已保留在输入框，不会替换原请求。"
internal const val STATUS_CONTEXT_INVALID = "上下文已不可用，请重新提出问题；原动作仍从历史回执核对。"
internal const val STATUS_RESET_DONE =
    "对话草案已清空。原银行确认项仍需单独取消，已执行动作和回执保留。"
internal const val INPUT_PLACEHOLDER = "说说你想了解哪笔支出…"
internal const val INPUT_HINT = "Enter 发送 · Shift + Enter 换行"
internal const val DEFAULT_SLOT_SUMMARY = "可承接月份、对象和待补参数；记忆不代表操作授权。"
internal const val MEMORY_DISCLAIMER = "记忆不代表操作授权。"

/** 建议 chips（§3.11 / §5.2 B-10..B-14）：显示文案 → 实际发送文本。 */
internal val SUGGESTIONS: List<Pair<String, String>> = listOf(
    "分析账单" to "分析2026-09账单",
    "查看代扣" to "查看代扣",
    "核对支出" to "核对大额支出",
)

/** 模式徽章（§3.8 / §5.2 B-64..B-69）。 */
internal fun modeBadge(mode: String?, bootstrapping: Boolean, sessionFailed: Boolean): String = when {
    bootstrapping -> "正在建立会话"
    sessionFailed -> "连接失败"
    else -> when (mode) {
        "LANGGRAPH_MOCK" -> "LangGraph · 离线模拟模型"
        "LANGGRAPH_MODEL" -> "LangGraph · 模型已配置"
        "FAKE_AGENT" -> "网关协议模拟"
        "OFFLINE_DEMO" -> "离线工具演示"
        "CONNECTION_VERIFIED" -> "模型连接已验证"
        "MODEL_CONFIGURED" -> "模型已配置 · 未验证"
        null, "" -> "正在建立会话"
        else -> "模型已配置 · 未验证"
    }
}

/** 模型信息小字（§3.8 / B-70..B-75）。 */
internal fun modeInfo(mode: String?, model: String?, sessionFailed: Boolean, errorText: String?): String = when {
    sessionFailed -> "初始化失败：${errorText ?: "无法连接服务器"}（可在「设置」检查服务器地址）"
    else -> when (mode) {
        "LANGGRAPH_MOCK" -> "正式 LangGraph 对话图已接入，当前使用离线模拟模型，不消耗 API 额度。"
        "LANGGRAPH_MODEL" -> "模型：${model.orEmpty()} · Java 核验业务事实与权限 · 模型调用有额度限制"
        "FAKE_AGENT" -> "当前连接模拟 Python 服务，验证只读工具、权限和恢复协议；未调用大模型。"
        "OFFLINE_DEMO" -> "未设置模型密钥。对话采用有限规则匹配，联合任务可通过表单创建，银行操作执行于本地模拟环境。"
        "CONNECTION_VERIFIED" -> "模型：${model.orEmpty()} · 本次进程已获得有效响应 · 写操作须逐项授权"
        "MODEL_CONFIGURED" -> "模型：${model.orEmpty()} · 尚未获得有效连接响应 · 写操作须逐项授权"
        else -> "正在恢复业务会话，请稍候。"
    }
}

/** 上下文状态（B-42..B-47）。 */
internal fun contextStateLabel(state: String?): String = when (state) {
    null, "" -> "从新问题开始"
    "ACTIVE" -> "上下文已保存"
    "NEEDS_CLARIFICATION" -> "等待补充参数"
    "AWAITING_CONFIRMATION" -> "待确认操作"
    "PAUSED" -> "已暂停"
    "EXPIRED" -> "上下文已过期"
    else -> "上下文已保存"
}

/** 意图（§3.1 Intent）。 */
internal fun intentLabel(intent: String?): String = when (intent) {
    null, "" -> "一般对话"
    "STATEMENT_ANALYSIS" -> "账单分析"
    "TRANSACTION_CHECK" -> "核对交易"
    "MONTH_COMPARISON" -> "月度对比"
    "TRANSFER_DRAFT" -> "转账草稿"
    "MANDATE_QUERY" -> "代扣查询"
    "MANDATE_REVOKE_DRAFT" -> "代扣撤销草稿"
    "SAVINGS_WORKFLOW" -> "储蓄任务"
    "ACTION_STATUS" -> "操作状态"
    "CLARIFICATION" -> "待补充参数"
    "GENERAL" -> "一般对话"
    else -> intent
}

/** 回答可信度（§3.9 answerSource）。 */
internal fun answerSourceLabel(source: String?): String? = when (source) {
    null, "" -> null
    "MODEL_GROUNDED" -> "模型回答 · 事实核验通过"
    "SAFE_FALLBACK" -> "模型回答未通过核验 · 已降级为工具数据说明"
    "ACTION_RECEIPT" -> "本轮生成了待确认操作卡"
    "PROGRAM_VERIFIED_FAKE_AGENT" -> "网关模式 · 程序核验结果"
    "PROGRAM_VERIFIED_LANGGRAPH" -> "LangGraph 模式 · 程序核验结果"
    else -> source
}

/** 工具名（B-28..B-41）。 */
internal fun toolLabel(tool: String?): String = when (tool?.trim()) {
    null, "" -> "业务查询"
    "get_statement", "statement" -> "查询账单"
    "get_accounts", "accounts" -> "查询余额"
    "list_mandates", "mandates", "query_mandates" -> "查询银行代扣"
    "get_recurring", "recurring" -> "查询重复扣费候选"
    "resolve_recipient" -> "核对收款人"
    "compute", "compute_savings" -> "计算业务数据"
    "compose_answer" -> "根据问题组织回答"
    "get_action_status", "get_action" -> "核对银行动作状态"
    "get_schedules", "schedules" -> "查询定时计划"
    "preview_transfer" -> "生成转账确认卡"
    "preview_mandate_revoke" -> "生成代扣撤销确认卡"
    "preview_schedule" -> "生成计划确认卡"
    "create_workflow" -> "创建逐项授权任务"
    "get_workflow", "workflow" -> "查询任务进度"
    else -> tool!!
}

/** trace 的一项：可能是 `tool` / `tool / CODE` / `tool / CODE / 说明`。 */
internal fun traceLabel(entry: String): String {
    val parts = entry.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return entry
    return buildString {
        append(toolLabel(parts.first()))
        parts.drop(1).forEach { part ->
            append(" · ")
            append(part)
        }
    }
}

/** 上下文槽位摘要（B-51..B-54）。 */
internal fun slotSummary(slots: Map<String, JsonElement>): String {
    val parts = mutableListOf<String>()
    slots["primary_month"].asPrimitiveString()?.let { parts.add("账单 $it") }
    slots["comparison_month"].asPrimitiveString()?.let { parts.add("比较 $it") }
    slots["selected_recipient_id"].asPrimitiveString()?.let { parts.add("收款人 $it") }
    slots["amount_minor"].asPrimitiveLong()?.let { parts.add("金额 ${Formatters.money(it)}") }
    slots["selected_mandate_ids"].asArraySize()?.takeIf { it > 0 }?.let { parts.add("已选代扣 $it 项") }
    slots["excluded_mandate_ids"].asArraySize()?.takeIf { it > 0 }?.let { parts.add("已排除代扣 $it 项") }
    slots["focused_transaction_id"].asPrimitiveString()?.let { parts.add("已选中核对交易") }
    slots["active_action_id"].asPrimitiveString()?.let { parts.add("原确认项与回执可继续核对") }
    slots["active_workflow_id"].asPrimitiveString()?.let { parts.add("联合任务进行中") }
    slots["missing_slots"].asArraySize()?.takeIf { it > 0 }?.let { parts.add("待补参数 $it 项") }
    return if (parts.isEmpty()) DEFAULT_SLOT_SUMMARY else parts.joinToString(" · ") + " · $MEMORY_DISCLAIMER"
}

private fun JsonElement?.asPrimitiveString(): String? {
    val element = this ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun JsonElement?.asPrimitiveLong(): Long? {
    val element = this ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asLong }.getOrNull()
}

private fun JsonElement?.asArraySize(): Int? {
    val element = this ?: return null
    if (!element.isJsonArray) return null
    return element.asJsonArray.size()
}

/** `data` 是 StatementVO 时解析成强类型，用于「本轮查询依据」摘要。 */
internal fun parseStatement(data: JsonElement?): StatementVo? {
    if (data == null || !data.isJsonObject) return null
    val obj = data.asJsonObject
    if (!obj.has("month") || !obj.has("expense_minor")) return null
    return runCatching { GSON.fromJson(data, StatementVo::class.java) }.getOrNull()
}

private val GSON = Gson()

/** 澄清候选收款人（B-62）。 */
data class RecipientCandidate(
    val name: String,
    val note: String,
    val label: String,
)

internal fun parseRecipientCandidates(clarification: JsonElement?): List<RecipientCandidate> {
    if (clarification == null || !clarification.isJsonObject) return emptyList()
    val array = clarification.asJsonObject.get("recipientCandidates") ?: return emptyList()
    if (!array.isJsonArray) return emptyList()
    return array.asJsonArray.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull null
        val obj = item.asJsonObject
        val name = obj.get("name").asPrimitiveString() ?: return@mapNotNull null
        RecipientCandidate(
            name = name,
            note = obj.get("note").asPrimitiveString().orEmpty(),
            label = obj.get("label").asPrimitiveString().orEmpty(),
        )
    }
}

/** modelUsage / timing 的取值（snake_case / camelCase 均有）。 */
internal fun JsonElement?.jsonInt(key: String): Int? {
    val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val value = obj.get(key) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asInt }.getOrNull()
}

internal fun JsonElement?.jsonLong(key: String): Long? {
    val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val value = obj.get(key) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asLong }.getOrNull()
}

internal fun JsonElement?.jsonBoolean(key: String): Boolean? {
    val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val value = obj.get(key) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asBoolean }.getOrNull()
}
