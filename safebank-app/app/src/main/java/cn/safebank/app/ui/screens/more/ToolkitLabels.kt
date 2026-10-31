package cn.safebank.app.ui.screens.more

import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.network.ApiException
import cn.safebank.app.data.util.Formatters

/**
 * 「工具与审计」页面共用的文案映射与错误处理。
 *
 * 约定（docs/api-contract §9 / docs/ui-design-spec §3.7/§3.8/§5）：
 * - 后端返回的业务 message 本身是可展示的中文，优先原样展示；
 * - 网络层/未知异常统一折算成中文兜底提示，绝不把英文堆栈抛给用户；
 * - 默认 profile 不支持 RED 验证（503 VERIFICATION_UNAVAILABLE）与模拟接口，追加明确指引。
 */

internal fun moneyOrDash(minor: Long?): String = if (minor == null) "—" else Formatters.money(minor)

/** 长编号折叠展示：前 8 位 + …（避免卡片横向溢出）。 */
internal fun shortId(id: String?): String {
    val value = id.orEmpty()
    return if (value.length <= 8) value else value.take(8) + "…"
}

/** CSRF 等敏感值脱敏展示。 */
internal fun maskSecret(value: String?): String {
    val text = value.orEmpty()
    return when {
        text.isEmpty() -> "—"
        text.length <= 8 -> "••••"
        else -> text.take(8) + "…" + text.takeLast(4)
    }
}

internal fun String?.containsCjk(): Boolean {
    val text = this ?: return false
    return text.any { it.code in 0x4E00..0x9FFF }
}

/** 「剩余 4 分 12 秒」；无法解析时返回 —。 */
internal fun remainingText(iso: String?): String {
    val seconds = Formatters.remainingSeconds(iso) ?: return "—"
    if (seconds <= 0) return "已过期"
    val minutes = seconds / 60
    return if (minutes > 0) "剩余 $minutes 分 ${seconds % 60} 秒" else "剩余 $seconds 秒"
}

/** 预览有效期提示文案。 */
internal fun expiryLine(iso: String?): String {
    if (iso.isNullOrBlank()) return "有效期未知"
    val seconds = Formatters.remainingSeconds(iso) ?: return "有效期未知"
    return if (seconds <= 0) {
        "预览已过期，请重新生成预览。"
    } else {
        "有效期至 ${Formatters.shortDateTime(iso)} · ${remainingText(iso)}"
    }
}

/** 会话模式 → 中文（ui-spec §3.8）。 */
internal fun modeLabel(mode: String?): String = when (mode) {
    null, "" -> "正在建立会话"
    "OFFLINE_DEMO" -> "离线工具演示"
    "MODEL_CONFIGURED" -> "模型已配置 · 未验证"
    "CONNECTION_VERIFIED" -> "模型连接已验证"
    "FAKE_AGENT" -> "网关协议模拟"
    "LANGGRAPH_MOCK" -> "LangGraph · 离线模拟模型"
    "LANGGRAPH_MODEL" -> "LangGraph · 模型已配置"
    else -> "模型已配置 · 未验证"
}

/** 动作种类 → 中文（ui-spec §3.6 标题）。 */
internal fun actionKindTitle(kind: String?): String = when (kind) {
    "TRANSFER" -> "转账操作"
    "REVOKE" -> "撤销银行侧代扣"
    "SCHEDULE" -> "创建定时转账"
    "QUERY_MANDATES" -> "查询本人银行代扣"
    else -> "待确认操作"
}

/** 动作状态 → 徽章语气。 */
internal fun actionStatusTone(status: String?): Tone = when (status) {
    "AWAITING_AUTH" -> Tone.Warning
    "AUTHORIZED" -> Tone.Info
    "VERIFIED_SUCCESS" -> Tone.Success
    "VERIFIED_FAILED" -> Tone.Danger
    "UNKNOWN" -> Tone.Warning
    "PENDING" -> Tone.Info
    "CANCELLED" -> Tone.Neutral
    else -> Tone.Neutral
}

/** 待确认操作卡的「下一步」提示（ui-spec §3.6 第 3 条）。 */
internal fun actionNextStepText(action: ActionVo?): String = when (action?.status) {
    "AWAITING_AUTH" -> "请先核对以下信息，再勾选确认。聊天中的确认不会执行操作。"
    "AUTHORIZED" -> "授权已记录，尚未提交。请核对最新状态后继续。"
    "CANCELLED" -> "本操作已取消。"
    "VERIFIED_SUCCESS" -> "操作已完成，请以查单结果为准。"
    "VERIFIED_FAILED" -> "本操作未完成，请展开详情查看原因。"
    "PENDING", "UNKNOWN" -> "请先查询原操作的最新状态，勿重复创建相同操作。"
    else -> "预览已生成，等待确认。"
}

/** 回执播报（ui-spec §3.7）。 */
internal fun receiptMessage(result: ActionExecutionResultVo): String = when (result.status) {
    "VERIFIED_SUCCESS" -> when (result.kind) {
        "QUERY_MANDATES" -> if (result.data != null) {
            "本次代扣查询已完成；查询没有转账或记账。"
        } else {
            "这是原查询回执，重新查询需再次确认范围。"
        }

        "SCHEDULE" -> "定时计划已登记，尚未发生转账。"
        "REVOKE" -> "银行侧代扣已撤销，商户合同状态未知，余额没有因此增加。"
        "TRANSFER" -> "转账已完成，并核验双边账务分录。"
        else -> "操作已完成。"
    }

    "VERIFIED_FAILED" -> "本操作未完成，请查看失败原因。"
    "PENDING" -> "银行已受理，处理中；请用「查询最新状态」按原操作编号查单。"
    "UNKNOWN" -> "结果未定；请勿重新创建相同操作，用原操作编号查单。"
    "AUTHORIZED" -> "授权已记录，尚未提交。"
    "AWAITING_AUTH" -> "预览已生成，等待确认。"
    "CANCELLED" -> "本操作已取消。"
    else -> "请以查单结果为准。"
}

/** 风险等级文案（ui-spec §3.6 第 6 条）。 */
internal fun riskLevelLabel(level: String?): String = when (level?.uppercase()) {
    "GREEN" -> "绿色 · 可自动查询"
    "YELLOW" -> "黄色 · 需要明确确认"
    "RED" -> "红色 · 需要验证后确认"
    else -> "风险等级未确认"
}

internal fun riskTone(level: String?): Tone = when (level?.uppercase()) {
    "GREEN" -> Tone.Success
    "YELLOW" -> Tone.Warning
    "RED" -> Tone.Danger
    else -> Tone.Neutral
}

/** 风险判定原因（reasonCode）中文。 */
internal fun reasonCodeLabel(code: String?): String? = when (code) {
    "DAILY_OUTGOING_THRESHOLD_EXCEEDED" -> "当日累计金额超过阈值"
    "EXPLICIT_CONFIRMATION_REQUIRED" -> "需要用户明确确认"
    "PRIOR_STEP_UP_REQUIREMENT" -> "需要补充验证后确认"
    null, "" -> null
    else -> code
}

internal fun riskExplanation(risk: RiskDecisionVo?): String {
    if (risk == null) return "暂无风险判定，请先刷新风险条件。"
    return if (risk.isRed) {
        "本次预计日累计 ${Formatters.money(risk.projectedMinor.coerceAtLeast(0L))}，超过 ¥1,000.00，需补充验证。"
    } else {
        "核对本次操作范围、对象与参数后确认。"
    }
}

/** 代扣计费周期（ui-spec §5.4 D-23..D-26）。 */
internal fun intervalLabel(interval: String?): String = when (interval?.uppercase()) {
    "MONTHLY" -> "按月"
    "QUARTERLY" -> "按季度"
    "YEARLY" -> "按年"
    else -> "周期未确认"
}

/** 银行侧代扣授权状态（ui-spec §5.4 D-27..D-29）。 */
internal fun mandateStatusLabel(status: String?): String = when (status?.uppercase()) {
    "ACTIVE" -> "银行侧授权有效"
    "REVOKED" -> "银行侧授权已撤销"
    else -> "银行侧状态未确认"
}

internal fun mandateStatusTone(status: String?): Tone = when (status?.uppercase()) {
    "ACTIVE" -> Tone.Success
    "REVOKED" -> Tone.Neutral
    else -> Tone.Warning
}

/** 审计事件 → 中文（docs/api-contract §3.4）。 */
internal fun auditEventLabel(event: String?): String = when (event) {
    "PREVIEWED" -> "已生成预览"
    "AUTHORIZATION_GRANTED" -> "已授权"
    "EXECUTED" -> "银行已执行"
    "CANCELLED" -> "已取消"
    "BANK_REJECTED" -> "银行明确拒绝"
    "BANK_ACCEPTED" -> "银行已受理"
    "BANK_PROCESSING_FAILED" -> "银行处理失败"
    "CONFIRMED_QUERY_EXECUTED" -> "查询回执已确认"
    null, "" -> "未知事件"
    else -> event
}

/** 业务/执行错误码 → 中文。 */
internal fun errorCodeLabel(code: String?): String? = when (code) {
    "INSUFFICIENT_FUNDS" -> "账户余额不足"
    "LIMIT_EXCEEDED" -> "超过演示单笔限额（¥10,000）"
    "ALREADY_REVOKED" -> "该代扣授权已撤销"
    "VALIDATION_ERROR" -> "参数或状态不合法"
    "NOT_FOUND" -> "资源不存在或不属于当前用户"
    "CONFLICT" -> "状态冲突：参数已变化或动作已受理"
    "AUTH_REQUIRED" -> "缺少有效授权，请先确认授权"
    "AUTH_EXPIRED" -> "预览或授权已过期，请重新生成预览"
    "RISK_DECISION_STALE" -> "风险判定已变化，请刷新风险条件"
    "STEP_UP_REQUIRED" -> "需要补充一次性验证"
    "VERIFICATION_INVALID" -> "验证码不正确或验证凭据无效"
    "VERIFICATION_EXPIRED" -> "验证码已过期，请重新发起验证"
    "VERIFICATION_REPLAYED" -> "验证凭据已被使用，请重新发起验证"
    "VERIFICATION_UNAVAILABLE" -> "当前后端未启用演示验证服务"
    "CHALLENGE_RATE_LIMITED" -> "验证码发送过于频繁，请稍后再试"
    "SECURITY_LOCKED" -> "安全锁定中，请稍后再试"
    "RISK_RECONCILIATION_REQUIRED" -> "存在未核对账务，需人工对账后才能继续"
    "SIMULATED_BANK_REJECTION" -> "模拟：银行明确拒绝"
    "RESPONSE_LOST" -> "模拟：执行后回执丢失（结果未知）"
    "LOOKUP_UNAVAILABLE" -> "模拟：查单不可用"
    "SIMULATION_DISABLED" -> "当前实例未启用模拟能力"
    "SESSION_REQUIRED" -> "会话已失效，请重新建立会话"
    "CSRF_REQUIRED" -> "请求校验失败（CSRF），请重新建立会话"
    "ORIGIN_DENIED" -> "请求来源被拒绝，请检查服务器地址"
    "NETWORK_ERROR" -> "网络连接失败"
    "SERVER_NOT_CONFIGURED" -> "服务器地址无效或未配置"
    "EMPTY_RESPONSE" -> "服务器返回空响应"
    "PARSE_ERROR" -> "响应解析失败"
    "QUERY_CONFIRMATION_REQUIRED" -> "读取代扣列表前需要确认查询范围"
    "ACTION_ALREADY_COMPLETED" -> "动作已终结，只能查单"
    "ACTION_RECONCILING" -> "动作核对中，请稍后查单"
    "CONTEXT_PROCESSING" -> "对话上下文正在处理，请稍后"
    "CONTEXT_INTERRUPTED" -> "对话上下文被中断，请核对后再试"
    "CONTEXT_EXPIRED" -> "对话上下文已过期"
    null, "" -> null
    else -> null
}

/**
 * 读取业务错误码（数据层约定失败统一包装成 [ApiException]；其他异常返回 null）。
 */
internal fun apiCodeOf(throwable: Throwable?): String? {
    val error = throwable as? ApiException ?: return null
    return error.code.takeIf { it.isNotBlank() }
}

/** 把任意失败折算成可展示的中文提示（永不抛异常）。 */
internal fun friendlyError(
    throwable: Throwable?,
    fallback: String = "请求未完成，请检查网络连接或稍后重试。",
): String {
    val error = throwable ?: return fallback
    val api = error as? ApiException
    val code = api?.code
    val raw = (api?.message ?: error.message).orEmpty().trim()
    val base = when {
        raw.containsCjk() -> raw
        api != null && !api.isNetworkError -> errorCodeLabel(code) ?: fallback
        else -> fallback
    }
    val hint = when {
        code == "VERIFICATION_UNAVAILABLE" ||
            raw.contains("未接可信验证服务") ||
            raw.contains("未配置可信验证服务") ||
            raw.contains("验证服务未完成投递") ->
            "（当前后端未启用演示验证，请以 risk-demo profile 启动后端）"

        code == "SIMULATION_DISABLED" ||
            raw.contains("模拟日期仅在独立实验实例启用") ||
            raw.contains("故障配置只在显式本地实验模式启用") ->
            "（模拟接口仅在 risk-demo profile 下启用）"

        code == "STEP_UP_REQUIRED" -> "（红色风险需先完成一次性验证码校验）"
        code == "AUTH_EXPIRED" -> "（预览已过期，请重新生成预览）"
        code == "RISK_DECISION_STALE" -> "（风险判定已变化，请刷新风险条件后重试）"
        code == "SESSION_REQUIRED" || code == "CSRF_REQUIRED" ->
            "（会话已失效，请到「设置」页重新建立会话）"

        code == "NETWORK_ERROR" || code == "SERVER_NOT_CONFIGURED" ->
            "（请确认服务器地址：模拟器用 http://10.0.2.2:8080，真机用电脑局域网 IP）"

        else -> ""
    }
    return base + hint
}
