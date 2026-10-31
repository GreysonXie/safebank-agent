package cn.safebank.app.ui.screens.actions

import cn.safebank.app.data.network.ApiException

/**
 * 页面层的错误翻译层。
 *
 * 数据层约定：所有 `Result.failure` 统一携带 `ApiException(code, message, httpStatus, body)`，
 * 其中 `message` 已经是后端返回的中文可读文案（见 docs/api-contract.md §3.2 / §9）。
 *
 * 这里只做两件事：
 * 1. 把异常安全地降级成中文文本（绝不把 `Result.failure` 往上抛，UI 不会崩）；
 * 2. 按业务 code 追加一句“下一步该做什么”的提示（契约里每个 code 都有明确的处置语义）。
 *
 * 说明：这里刻意集中唯一一处对 `ApiException` 的引用，便于 Lead 在数据层落地后统一核对。
 */

/** 业务错误码；非 ApiException（网络、解析、未实现等）返回 null。 */
internal fun Throwable.apiCode(): String? {
    val e = this as? ApiException ?: return null
    return e.code?.takeIf { it.isNotBlank() }
}

/** 统一的中文失败文案：`{message}（{code}）`；没有 message 时给通用兜底。 */
internal fun failureText(t: Throwable): String {
    val code = t.apiCode()
    val raw = t.message?.takeIf { it.isNotBlank() }
    val base = when {
        t is NotImplementedError -> "数据层尚未接入，该接口暂不可用。"
        raw != null -> raw
        else -> "操作失败，请稍后重试。"
    }
    return if (code != null) "$base（$code）" else base
}

internal fun failureHint(t: Throwable): String? = codeHint(t.apiCode())

/** 按业务 code 给出处置建议（契约 §9 / §6）。未知 code 返回 null。 */
internal fun codeHint(code: String?): String? = when (code) {
    // ---- 对话 / 上下文 ----
    "CONTEXT_PROCESSING" -> "原请求仍在处理中。请稍候再点「核对原请求」，不会自动重发。"
    "CONTEXT_INTERRUPTED" -> "上次请求未完成。请先核对关联的银行动作（待确认操作与回执），不要直接重放。"
    "CONTEXT_EXPIRED" -> "对话上下文已过期。新问题会建立新上下文；已生成的银行动作仍可在「待确认操作与回执」核对。"
    "NOT_FOUND" -> "对话上下文不存在。新问题会建立新的上下文。"
    "CONFLICT" -> "上下文版本已改变或请求编号冲突。请核对最新状态后再发送。"
    "AGENT_RECONCILING" -> "智能体正在对账。请稍后用同一请求编号核对原请求。"
    "AGENT_REVIEW_REQUIRED" -> "智能体结果需要人工核对，请展开「查看本轮查询依据」。"
    "AGENT_BUDGET_EXCEEDED" -> "智能体预算已用尽，请稍后重试或换一种问法。"
    "TOOL_QUOTA_EXCEEDED" -> "工具调用额度已用尽，请稍后重试。"
    // ---- 会话 / 安全 ----
    "SESSION_REQUIRED" -> "会话已失效，已尝试重建；若仍失败请到「设置」检查服务器地址。"
    "CSRF_REQUIRED", "ORIGIN_DENIED" -> "安全令牌失效，请刷新会话后重试。"
    // ---- 模型侧 ----
    "MODEL_NOT_CONFIGURED" -> "后端未配置模型密钥，当前为离线工具演示模式。"
    "MODEL_UNAVAILABLE", "MODEL_API_ERROR", "MODEL_INTERRUPTED" -> "模型服务暂时不可用，可稍后重试或改用简单问答。"
    "MODEL_RESPONSE_INVALID", "ANSWER_NOT_GROUNDED", "ANSWER_REVIEW_CONTRACT_INVALID" ->
        "模型回答未通过事实核验，已降级为工具数据说明。"
    "MODEL_TURN_BUDGET_EXCEEDED", "MODEL_REQUEST_BUDGET_EXCEEDED" -> "本轮模型调用额度已用尽，请稍后重试。"
    // ---- 风险 / 验证 ----
    "VERIFICATION_UNAVAILABLE" -> VERIFICATION_UNAVAILABLE_TEXT
    "STEP_UP_REQUIRED" -> "该操作属于红色风险，需要先完成一次性验证码校验。"
    "RISK_DECISION_STALE" -> "风险判定已变化，请刷新风险条件后重新确认。"
    "RISK_RECONCILIATION_REQUIRED" -> "存在未对账的银行动作，请先到「实验与审计」核对后再操作。"
    "AUTH_EXPIRED" -> "预览或授权已过期，请重新生成操作预览。"
    "AUTH_REQUIRED" -> "缺少有效授权，请重新勾选确认。"
    "SECURITY_LOCKED" -> "安全锁定中，请稍后再试。"
    "CHALLENGE_RATE_LIMITED" -> "验证码请求过于频繁，请 60 秒后重试。"
    "VERIFICATION_INVALID" -> "验证码不正确；连续错误会触发临时锁定。"
    "VERIFICATION_EXPIRED" -> "验证码已过期，请重新发起验证。"
    "VERIFICATION_REPLAYED" -> "验证凭据已被使用，请重新发起验证。"
    // ---- 参数 / 业务 ----
    "VALIDATION_ERROR" -> "请求参数不合法，请核对输入后重试。"
    "LIMIT_EXCEEDED" -> "超出演示单笔限额（¥10,000）。"
    "INSUFFICIENT_FUNDS" -> "账户余额不足。"
    "ALREADY_REVOKED" -> "该代扣已撤销，无需重复操作。"
    "QUERY_CONFIRMATION_REQUIRED" -> "读取代扣列表必须先走「确认查询范围」的查询动作。"
    "SIMULATION_DISABLED" -> "当前 profile 未启用模拟能力，请用 risk-demo profile 启动后端。"
    else -> null
}

internal const val VERIFICATION_UNAVAILABLE_TEXT =
    "当前后端未启用演示验证（默认 profile 会返回 VERIFICATION_UNAVAILABLE）。请用 risk-demo profile 启动后端后再试。"

/** 动作失败原因码 → 中文（submit 返回 status=VERIFIED_FAILED 时使用）。 */
internal fun actionErrorCodeLabel(code: String?): String = when (code) {
    null, "" -> "未知原因"
    "INSUFFICIENT_FUNDS" -> "余额不足"
    "LIMIT_EXCEEDED" -> "超出单笔限额"
    "ALREADY_REVOKED" -> "代扣已撤销"
    "VALIDATION_ERROR" -> "参数校验失败"
    "AUTH_REQUIRED" -> "缺少有效授权"
    "AUTH_EXPIRED" -> "授权已过期"
    "STEP_UP_REQUIRED" -> "需要补充验证"
    "RISK_DECISION_STALE" -> "风险判定已变化"
    "ACCOUNT_NOT_FOUND" -> "账户不存在"
    "RECIPIENT_NOT_FOUND" -> "收款人不存在"
    "NOT_SUBMITTED" -> "银行确认未提交"
    else -> code
}
