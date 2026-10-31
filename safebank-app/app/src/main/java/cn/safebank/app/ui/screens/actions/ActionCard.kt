package cn.safebank.app.ui.screens.actions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.ChallengeVo
import cn.safebank.app.data.model.MandateVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.model.VerificationProofVo
import cn.safebank.app.data.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 逐动作授权 / 提交 / 查单 / 取消 —— 全部逻辑集中在这里（契约 §4.14–§4.21、§6.2、§6.6）。
 *
 * 对话页与「待确认操作与回执」页复用的同一张卡片，避免两份流程实现。
 *
 * 铁律（契约 §6.2、§6.6）：
 * - 授权与执行是两个独立请求，授权后不自动提交；
 * - `payloadHash` 必须来自同一个预览响应；
 * - `decisionId` 必须是当前判定：`needsRefresh == true` 或为空时，先 `POST /risk/preview`；
 * - RED 必须先 `createChallenge → verifyChallenge → verificationProofId` 才能 authorize；
 * - 已提交（PENDING/UNKNOWN）只能 `GET /api/actions/{id}` 查单，不重新 submit。
 */

private object ActionPalette {
    val red = Color(0xFFB42318)
    val redBg = Color(0xFFFFF1F0)
    val yellow = Color(0xFF8A6100)
    val yellowBg = Color(0xFFFFF7E7)
    val green = Color(0xFF166534)
    val greenBg = Color(0xFFECFDF3)
    val blue = Color(0xFF1A4F99)
    val blueBg = Color(0xFFF1F5F9)
}

internal val OPERABLE_STATUSES = setOf("AWAITING_AUTH", "AUTHORIZED")

internal fun actionKindTitle(kind: String): String = when (kind) {
    "TRANSFER" -> "转账操作"
    "REVOKE" -> "撤销银行侧代扣"
    "SCHEDULE" -> "创建定时转账"
    "QUERY_MANDATES" -> "查询本人银行代扣"
    else -> Formatters.actionKindLabel(kind)
}

internal fun actionStatusBadge(status: String): String = when (status) {
    "AWAITING_AUTH" -> "待确认 · 尚未执行"
    "AUTHORIZED" -> "已授权 · 尚未提交"
    "VERIFIED_SUCCESS" -> "已核验完成"
    "VERIFIED_FAILED" -> "已核验失败"
    "CANCELLED" -> "已取消"
    "PENDING" -> "银行处理中"
    "UNKNOWN" -> "状态待核实"
    else -> Formatters.actionStatusLabel(status)
}

internal fun nextStepText(status: String): String = when (status) {
    "AWAITING_AUTH" -> "请先核对以下信息，再勾选确认。聊天中的确认不会执行操作。"
    "AUTHORIZED" -> "授权已记录，尚未提交。请核对最新状态后继续。"
    "CANCELLED" -> "本操作已取消。"
    "VERIFIED_FAILED" -> "本操作未完成，请展开详情查看原因。"
    else -> "请先查询原操作的最新状态，勿重复创建相同操作。"
}

/** 风险判定原因（reasonCode）中文说明（契约 §3.6）。 */
internal fun riskReasonLabel(code: String?): String? = when (code) {
    null, "" -> null
    "DAILY_OUTGOING_THRESHOLD_EXCEEDED" -> "超过当日累计支出阈值（¥1,000.00）"
    "EXPLICIT_CONFIRMATION_REQUIRED" -> "需要用户明确确认本次操作"
    "PRIOR_STEP_UP_REQUIREMENT" -> "沿用上一次的强验证要求"
    else -> code
}

/** 回执文案矩阵（ui-design-spec §3.7 / §5.8 H-60..H-66）。 */
internal fun receiptTextFor(r: ActionExecutionResultVo): String? = when (r.status) {
    "VERIFIED_SUCCESS" -> when (r.kind) {
        "QUERY_MANDATES" ->
            if (r.data != null) "本次代扣查询已完成；查询没有转账或记账。"
            else "这是原查询回执，重新查询需再次确认范围。"
        "SCHEDULE" -> "定时计划已登记，尚未发生转账。"
        "REVOKE" -> "银行侧代扣已撤销，商户合同状态未知，余额没有因此增加。"
        "TRANSFER" -> "转账已完成，并核验双边账务分录。"
        else -> "已核验完成。请以查单结果为准，勿重新创建相同动作。"
    }
    "VERIFIED_FAILED" ->
        "已核验失败（${actionErrorCodeLabel(r.errorCode)}）。请以查单结果为准，勿重新创建相同动作。"
    "PENDING" -> "银行处理中。请使用「查询最新状态」确认结果，勿重复创建相同操作。"
    "UNKNOWN" -> "结果未知。请按原操作编号「查询最新状态」，不要新建相同操作。"
    "CANCELLED" -> "本操作已取消。"
    "AWAITING_AUTH" ->
        if (r.observation == "NOT_SUBMITTED") "银行侧确认未提交，操作已回退为待确认，请核对后重新提交。" else null
    else -> null
}

private fun accountLabel(id: String?): String = when (id) {
    null, "" -> "—"
    "checking" -> "日常账户"
    "travel" -> "我的旅游账户"
    else -> id
}

private fun recipientDisplay(id: String?): String = when (id) {
    null, "" -> "—"
    "zhang-a" -> "张三 · 同学"
    "zhang-b" -> "张三 · 同事"
    "travel-recipient" -> "我的旅游账户"
    else -> id
}

private fun mandateStatusLabel(status: String?): String = when (status) {
    "ACTIVE" -> "银行侧授权有效"
    "REVOKED" -> "银行侧授权已撤销"
    else -> "银行侧状态未确认"
}

// ------------------------------------------------------------------ 卡片状态

/**
 * 单张待确认操作卡的 UI 状态机。
 * 服务端 `status` 是唯一事实来源；本地只维护「勾选 / 验证凭据 / 错误条」三份 UI 态。
 */
class ActionCardState(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
    initial: ActionVo,
) {

    var action by mutableStateOf(initial)
        private set

    /** 提交/查单回执（null = 尚未提交或刚取消）。 */
    var result by mutableStateOf<ActionExecutionResultVo?>(null)
        private set

    /** 当前生效的风险判定（可能来自 refreshRiskPreview 的权威结果）。 */
    var security by mutableStateOf<RiskDecisionVo?>(initial.security)
        private set

    var confirmed by mutableStateOf(false)
    var codeInput by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var statusText by mutableStateOf<String?>(null)
        private set

    var challenge by mutableStateOf<ChallengeVo?>(null)
        private set
    var proof by mutableStateOf<VerificationProofVo?>(null)
        private set
    var receiptText by mutableStateOf<String?>(null)
        private set

    val id: String get() = action.id
    val kind: String get() = action.kind

    /** 以服务端状态为准：提交/查单后的回执状态覆盖初始预览状态。 */
    val effectiveStatus: String
        get() = result?.status?.takeIf { it.isNotBlank() } ?: action.status

    /** 终态卡（成功 / 已取消）归档到历史记录，不再留在待确认区。 */
    val isArchived: Boolean
        get() = effectiveStatus == "VERIFIED_SUCCESS" || effectiveStatus == "CANCELLED"

    val requiresVerification: Boolean get() = security?.isRed == true

    val showVerification: Boolean
        get() = requiresVerification && effectiveStatus == "AWAITING_AUTH"

    /** 确认按钮启用条件：勾选 + 可操作状态 + （RED 时）已有验证凭据。 */
    val canConfirm: Boolean
        get() = !busy && effectiveStatus in OPERABLE_STATUSES && confirmed &&
            (!requiresVerification || effectiveStatus == "AUTHORIZED" || proof != null)

    val primaryLabel: String
        get() = when {
            effectiveStatus == "AUTHORIZED" -> "提交执行"
            kind == "QUERY_MANDATES" -> "确认并查询"
            else -> "确认并提交"
        }

    // ------------------------------------------------------------ 主流程

    /**
     * 确认并提交（契约 §6.2 两步授权）：
     * 刷新风险（必要时）→ RED 验证 → authorize → submit → 回执。
     */
    fun confirmAndSubmit() {
        if (busy) return
        if (effectiveStatus !in OPERABLE_STATUSES) {
            errorText = "当前状态为「${actionStatusBadge(effectiveStatus)}」，只能查询最新状态，不能重复提交。"
            return
        }
        if (!confirmed) {
            errorText = "请先勾选「我已核对本次参数，并明确确认操作」。"
            return
        }
        if (requiresVerification && effectiveStatus == "AWAITING_AUTH" && proof == null) {
            errorText = "本次操作属于红色风险，请先完成一次性验证码校验。"
            return
        }
        scope.launch {
            busy = true
            errorText = null
            statusText = null
            try {
                val current = action
                if (current.payloadHash.isBlank()) {
                    errorText = "该操作缺少参数指纹（payload_hash），无法授权。请重新生成操作预览。"
                    return@launch
                }

                // 1) 拿权威 decisionId（needsRefresh / decisionId 为空时）
                var decision = security
                if (decision == null || decision.needsRefresh || decision.decisionId.isNullOrBlank()) {
                    statusText = "正在刷新风险条件…"
                    val refreshed = repository.refreshRiskPreview(current.id)
                    val failure = refreshed.exceptionOrNull()
                    if (failure != null) {
                        applyFailure(failure, "刷新风险条件失败")
                        return@launch
                    }
                    decision = refreshed.getOrNull()
                    security = decision
                    confirmed = false
                }
                val decisionId = decision?.decisionId
                if (decision == null || decisionId.isNullOrBlank()) {
                    errorText = "风险判定缺少 decisionId，无法授权。请点「刷新风险条件」后再确认。"
                    return@launch
                }
                if (decision.needsRefresh) {
                    errorText = "风险条件仍需刷新，请点「刷新风险条件」后再确认。"
                    return@launch
                }

                // 2) RED：必须有验证凭据，否则引导去验证
                if (decision.isRed && effectiveStatus == "AWAITING_AUTH" && proof == null) {
                    errorText = "本次操作属于红色风险，请先完成一次性验证码校验。"
                    statusText = "请先点「发起验证」，再用 6 位演示验证码完成校验。"
                    return@launch
                }

                // 3) 授权（不自动提交）
                statusText = "正在记录授权…"
                val authResult = repository.authorize(
                    id = current.id,
                    payloadHash = current.payloadHash,
                    decisionId = decisionId,
                    verificationProofId = proof?.verificationProofId,
                    userConfirmed = true,
                )
                val authFailure = authResult.exceptionOrNull()
                if (authFailure != null) {
                    handleAuthorizeFailure(authFailure)
                    return@launch
                }
                action = authResult.getOrThrow()
                security = action.security ?: decision
                confirmed = false

                // 4) 提交银行（授权 ≠ 执行）
                statusText = "已授权，正在提交银行…"
                val submitResult = repository.submit(current.id)
                val submitFailure = submitResult.exceptionOrNull()
                if (submitFailure != null) {
                    applyFailure(submitFailure, "提交失败")
                    // 契约 §6.6：提交类失败不重试，改为读回同一动作的最新状态
                    synchronizeFromServer()
                    return@launch
                }
                applyResult(submitResult.getOrThrow(), fromSubmit = true)
            } finally {
                busy = false
            }
        }
    }

    /** 授权失败的分支处理（§4.19 错误表）。 */
    private suspend fun handleAuthorizeFailure(t: Throwable) {
        errorText = composeError(t)
        when (t.apiCode()) {
            "STEP_UP_REQUIRED" -> {
                statusText = "需要补充验证：请先发起验证并输入 6 位验证码，再重新勾选确认。"
                security = security?.copy(needsRefresh = true)
            }
            "RISK_DECISION_STALE" -> {
                security = security?.copy(needsRefresh = true)
                confirmed = false
                statusText = "风险条件已变化，请刷新风险条件并重新确认。"
            }
            "VERIFICATION_UNAVAILABLE" -> statusText = VERIFICATION_UNAVAILABLE_TEXT
            "AUTH_EXPIRED" -> statusText = "本次预览已过期。请重新生成操作预览后再确认。"
            "AUTH_REQUIRED" -> {
                confirmed = false
                statusText = "请重新勾选确认后再提交。"
            }
            "CONFLICT" -> statusText = "操作参数或状态已变化，请点「查询最新状态」核对。"
            "SECURITY_LOCKED" -> statusText = "安全锁定中，请稍后再试。"
        }
    }

    /** 刷新风险条件（POST /risk/preview，needsRefresh 恒为 false 的权威入口）。 */
    fun refreshRisk() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            try {
                val refreshed = repository.refreshRiskPreview(id)
                val failure = refreshed.exceptionOrNull()
                if (failure != null) {
                    applyFailure(failure, "刷新风险条件失败")
                    return@launch
                }
                val decision = refreshed.getOrThrow()
                security = decision
                action = action.copy(security = decision)
                confirmed = false
                proof = null
                challenge = null
                codeInput = ""
                statusText = "风险条件已刷新，请重新核对并勾选确认。"
            } finally {
                busy = false
            }
        }
    }

    /** 发起验证挑战（仅 RED）。默认 profile 会 503 VERIFICATION_UNAVAILABLE。 */
    fun beginVerification() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            try {
                beginVerificationInternal()
            } finally {
                busy = false
            }
        }
    }

    private suspend fun beginVerificationInternal() {
        val current = action
        var decision = security
        if (decision == null || decision.needsRefresh || decision.decisionId.isNullOrBlank()) {
            val refreshed = repository.refreshRiskPreview(current.id)
            val failure = refreshed.exceptionOrNull()
            if (failure != null) {
                applyFailure(failure, "刷新风险条件失败")
                return
            }
            decision = refreshed.getOrNull()
            security = decision
        }
        if (decision == null) {
            errorText = "无法获取风险判定，请稍后重试。"
            return
        }
        if (!decision.isRed) {
            errorText = "只有红色风险操作才需要验证码校验。"
            return
        }
        val decisionId = decision.decisionId
        if (decisionId.isNullOrBlank()) {
            errorText = "风险判定缺少 decisionId，无法发起验证。"
            return
        }
        val created = repository.createChallenge(current.id, current.payloadHash, decisionId)
        val failure = created.exceptionOrNull()
        if (failure != null) {
            errorText = failureText(failure)
            when (failure.apiCode()) {
                "VERIFICATION_UNAVAILABLE" -> statusText = VERIFICATION_UNAVAILABLE_TEXT
                "CHALLENGE_RATE_LIMITED" -> statusText = "验证码请求过于频繁，请 60 秒后重试。"
                "SECURITY_LOCKED" -> statusText = "安全锁定中，请稍后再试。"
                "RISK_DECISION_STALE" -> {
                    security = decision.copy(needsRefresh = true)
                    statusText = "风险条件已变化，请刷新后重新发起验证。"
                }
                "CONFLICT" -> statusText = "当前状态不允许发起验证，请查询最新状态。"
            }
            return
        }
        challenge = created.getOrThrow()
        proof = null
        statusText = "验证已发起。请从独立本机验证台取得 6 位演示码后输入。"
    }

    /** 校验 6 位验证码（§4.18）。 */
    fun verifyCode() {
        val currentChallenge = challenge ?: return
        val code = codeInput.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            errorText = "请输入 6 位数字验证码。"
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            try {
                val verified = repository.verifyChallenge(id, currentChallenge.challengeId, code)
                val failure = verified.exceptionOrNull()
                if (failure != null) {
                    errorText = failureText(failure)
                    when (failure.apiCode()) {
                        "VERIFICATION_INVALID" ->
                            statusText = "验证码不正确，请重新输入（连续错误会触发临时锁定）。"
                        "VERIFICATION_EXPIRED", "VERIFICATION_REPLAYED", "RISK_DECISION_STALE" -> {
                            challenge = null
                            codeInput = ""
                            statusText = "验证已失效，请重新发起验证。"
                        }
                        "SECURITY_LOCKED" -> statusText = "安全锁定中，请稍后重试。"
                    }
                    return@launch
                }
                proof = verified.getOrThrow()
                challenge = null
                codeInput = ""
                confirmed = false
                statusText = "验证已通过。尚未授权或执行，请再次核对并勾选确认。"
            } finally {
                busy = false
            }
        }
    }

    /** 查单（PENDING/UNKNOWN 时唯一安全的动作）。 */
    fun lookup() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            try {
                val looked = repository.action(id)
                val failure = looked.exceptionOrNull()
                if (failure != null) {
                    applyFailure(failure, "查询最新状态失败")
                    return@launch
                }
                val receipt = looked.getOrThrow()
                applyResult(receipt, fromSubmit = false)
                if (receipt.observation == "NOT_SUBMITTED") {
                    statusText = "银行侧确认未提交，操作已回退为待确认，可核对后重新提交。"
                }
            } finally {
                busy = false
            }
        }
    }

    /** 取消（仅未受理动作可取消）。 */
    fun cancelAction() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            try {
                val cancelled = repository.cancel(id)
                val failure = cancelled.exceptionOrNull()
                if (failure != null) {
                    applyFailure(failure, "取消失败")
                    return@launch
                }
                action = cancelled.getOrThrow()
                result = null
                receiptText = null
                confirmed = false
                proof = null
                challenge = null
                codeInput = ""
                statusText = null
            } finally {
                busy = false
            }
        }
    }

    /** 提交失败后读回服务端真实状态（用同一个 action id，绝不重新预览）。 */
    private suspend fun synchronizeFromServer() {
        repository.action(id).onSuccess { receipt ->
            applyResult(receipt, fromSubmit = false)
        }
    }

    /** 应用一份回执（提交 / 查单 / 列表回填共用）。 */
    fun applyResult(receipt: ActionExecutionResultVo, fromSubmit: Boolean = false) {
        result = receipt
        action = receipt.toActionVo()
        security = receipt.security ?: security
        receiptText = receiptTextFor(receipt)
        statusText = null
        if (receipt.observation == "NOT_SUBMITTED") {
            // 银行确认未提交 → 动作被回退成待确认，必须重新勾选后再提交
            confirmed = false
            proof = null
        }
        when (receipt.status) {
            "UNKNOWN" ->
                statusText = "结果未知。请按原操作编号点「查询最新状态」核对，不要新建相同操作。"
            "PENDING" -> statusText = "银行已受理，处理中。可稍后点「查询最新状态」。"
            "VERIFIED_FAILED" ->
                statusText = "银行明确失败（${actionErrorCodeLabel(receipt.errorCode)}），请核对后决定是否重新发起。"
            "VERIFIED_SUCCESS" ->
                if (receipt.kind == "QUERY_MANDATES" && fromSubmit) {
                    statusText = "查询已确认，可回到对话继续提问。"
                }
        }
    }

    private fun applyFailure(t: Throwable, prefix: String) {
        errorText = "$prefix：${failureText(t)}"
        failureHint(t)?.let { hint -> statusText = hint }
    }

    private fun composeError(t: Throwable): String {
        val hint = failureHint(t)
        return if (hint != null) "${failureText(t)}。$hint" else failureText(t)
    }
}

// ------------------------------------------------------------------ 卡片 UI

@Composable
fun ActionCard(card: ActionCardState, modifier: Modifier = Modifier) {
    var detailExpanded by remember(card.id) { mutableStateOf(false) }
    val status = card.effectiveStatus

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {

            card.errorText?.let { text ->
                ErrorBar(text)
                Spacer(Modifier.height(8.dp))
            }

            // 1. 标题行（操作类型 + 状态徽章）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = actionKindTitle(card.kind),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                ActionStatusBadge(text = actionStatusBadge(status), status = status)
            }

            Spacer(Modifier.height(8.dp))
            // 2. 下一步提示（成功/终态时显示回执文案）
            NextStepBar(card.receiptText ?: nextStepText(status), status)

            if (card.isArchived) {
                TextButton(onClick = { detailExpanded = !detailExpanded }, enabled = !card.busy) {
                    Text(if (detailExpanded) "收起回执详情" else "查看回执详情", fontSize = 12.sp)
                }
                if (detailExpanded) {
                    ActionParams(card)
                    ReceiptExtras(card)
                    TechFold(card)
                    card.security?.let { decision ->
                        Spacer(Modifier.height(10.dp))
                        RiskBar(decision)
                    }
                    MandateResultList(card)
                }
            } else {
                Spacer(Modifier.height(10.dp))
                ActionParams(card)
                ReceiptExtras(card)
                Spacer(Modifier.height(6.dp))
                TechFold(card)

                card.security?.let { decision ->
                    Spacer(Modifier.height(10.dp))
                    RiskBar(decision)
                }

                if (card.showVerification) {
                    Spacer(Modifier.height(10.dp))
                    VerificationArea(card)
                }

                if (status == "AWAITING_AUTH" || status == "AUTHORIZED") {
                    Spacer(Modifier.height(8.dp))
                    ConfirmRow(card)
                }

                Spacer(Modifier.height(12.dp))
                ActionButtons(card)
                MandateResultList(card)
            }

            card.statusText?.let { line ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = line,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorBar(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(10.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ActionStatusBadge(text: String, status: String) {
    val pair: Pair<Color, Color> = when (status) {
        "VERIFIED_SUCCESS" -> ActionPalette.greenBg to ActionPalette.green
        "VERIFIED_FAILED" -> ActionPalette.redBg to ActionPalette.red
        "PENDING", "UNKNOWN" -> ActionPalette.yellowBg to ActionPalette.yellow
        "CANCELLED" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        else -> ActionPalette.blueBg to ActionPalette.blue
    }
    Surface(color = pair.first, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 11.sp,
            color = pair.second,
        )
    }
}

@Composable
private fun NextStepBar(text: String, status: String) {
    val barColor = when (status) {
        "VERIFIED_SUCCESS" -> ActionPalette.green
        "VERIFIED_FAILED" -> ActionPalette.red
        "PENDING", "UNKNOWN" -> ActionPalette.yellow
        "CANCELLED" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(barColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionParams(card: ActionCardState) {
    val rows = actionParams(card.action)
    if (rows.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row -> ParamRow(row.first, row.second) }
        }
    }
    val remaining = Formatters.remainingSeconds(card.action.expiresAt)
    if (remaining != null && !card.isArchived) {
        ParamRow(
            label = "预览有效期",
            value = if (remaining <= 0) "已过期，请刷新风险条件或重新生成操作"
            else "剩余约 ${remaining / 60} 分 ${remaining % 60} 秒",
        )
    }
}

private fun actionParams(a: ActionVo): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    when (a.kind) {
        "TRANSFER" -> {
            a.sourceId?.let { rows.add("来源账户" to accountLabel(it)) }
            val recipient = a.recipientLabel?.takeIf { it.isNotBlank() } ?: a.recipientId
            recipient?.let { rows.add("收款对象" to it) }
            a.amountMinor?.let { rows.add("金额" to Formatters.money(it)) }
        }
        "SCHEDULE" -> {
            a.sourceId?.let { rows.add("来源账户" to accountLabel(it)) }
            a.recipientId?.let { rows.add("收款对象" to recipientDisplay(it)) }
            a.amountMinor?.let { rows.add("单期金额" to Formatters.money(it)) }
            a.firstDate?.let { rows.add("首次日期" to it) }
            a.endDate?.let { rows.add("结束日期" to it) }
            a.dayOfMonth?.let {
                rows.add("执行规则" to "每月 $it 日 · ${a.timezone ?: "Asia/Shanghai"}")
            }
            a.insufficientFunds?.let { rows.add("资金不足策略" to it) }
        }
        "REVOKE" -> {
            a.merchant?.let { rows.add("代扣商户" to it) }
            a.chargeAmountMinor?.let { rows.add("单期金额" to Formatters.money(it)) }
            a.billingInterval?.let { rows.add("扣费周期" to Formatters.billingIntervalLabel(it)) }
            a.estimatedMonthlySavingMinor?.takeIf { it > 0 }?.let {
                rows.add("预计月度节省" to Formatters.money(it))
            }
            a.scope?.let { rows.add("影响" to it) }
        }
        "QUERY_MANDATES" ->
            rows.add("查询范围" to "本人银行代扣（${a.query ?: "OWN_BANK_MANDATES"}）")
    }
    a.feeMinor?.takeIf { it > 0 }?.let { rows.add("手续费" to Formatters.money(it)) }
    if (a.currency != null && a.currency != "CNY") {
        rows.add("币种" to (a.currency ?: ""))
    }
    return rows
}

@Composable
private fun ParamRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ReceiptExtras(card: ActionCardState) {
    val receipt = card.result ?: return
    if (receipt.observation != null) {
        ParamRow(
            "观测结果",
            if (receipt.observation == "NOT_SUBMITTED") "银行确认未提交" else (receipt.observation ?: "—"),
        )
    }
    if (!receipt.observedAt.isNullOrBlank()) {
        ParamRow("查单时间", Formatters.shortDateTime(receipt.observedAt))
    }
    if (!receipt.queriedAt.isNullOrBlank()) {
        ParamRow("查询时间", Formatters.shortDateTime(receipt.queriedAt))
    }
    if (!receipt.errorCode.isNullOrBlank()) {
        ParamRow("失败原因", actionErrorCodeLabel(receipt.errorCode))
    }
}

@Composable
private fun TechFold(card: ActionCardState) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, enabled = !card.busy) {
        Text(if (expanded) "收起操作详情" else "查看操作详情", fontSize = 12.sp)
    }
    if (!expanded) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            ParamRow("操作编号", card.id.ifBlank { "—" })
            val receiptId = card.result?.receiptId ?: card.action.receiptId
            ParamRow("回执编号", receiptId?.takeIf { it.isNotBlank() } ?: "尚未生成")
            val errorCode = card.result?.errorCode ?: card.action.errorCode
            ParamRow("状态说明", if (errorCode.isNullOrBlank()) "无" else actionErrorCodeLabel(errorCode))
            Text(
                text = "参数指纹 ${card.action.payloadHash.take(16)}…",
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RiskBar(decision: RiskDecisionVo) {
    val title: String
    val body: String
    val bg: Color
    val fg: Color
    when {
        decision.isRed -> {
            title = "红色 · 需要验证后确认"
            body = "本次预计日累计 ${Formatters.money(decision.projectedMinor)}，超过 ¥1,000.00，需补充验证。"
            bg = ActionPalette.redBg
            fg = ActionPalette.red
        }
        decision.isYellow -> {
            title = "黄色 · 需要明确确认"
            body = "核对本次操作范围、对象与参数后确认。"
            bg = ActionPalette.yellowBg
            fg = ActionPalette.yellow
        }
        else -> {
            title = "绿色 · 可自动查询"
            body = "核对本次操作范围、对象与参数后确认。"
            bg = ActionPalette.blueBg
            fg = ActionPalette.blue
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Spacer(Modifier.height(2.dp))
            Text(text = body, fontSize = 12.sp, color = fg)
            riskReasonLabel(decision.reasonCode)?.let { reason ->
                Spacer(Modifier.height(2.dp))
                Text(text = "判定原因：$reason", fontSize = 10.sp, color = fg)
            }
            if (decision.isRed && decision.verificationMode == "LOCAL_DEMO_ONLY") {
                Spacer(Modifier.height(2.dp))
                Text(text = "本地合成验证演示", fontSize = 10.sp, color = fg)
            }
            if (decision.needsRefresh) {
                Spacer(Modifier.height(2.dp))
                Text(text = "风险条件已过期，需先刷新。", fontSize = 10.sp, color = fg)
            }
        }
    }
}

@Composable
private fun VerificationArea(card: ActionCardState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = "红色风险 · 一次性验证码", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            val verifiedProof = card.proof
            val activeChallenge = card.challenge
            when {
                verifiedProof != null -> Text(
                    text = "验证已通过。尚未授权或执行，请再次核对并勾选确认。",
                    fontSize = 11.sp,
                    color = ActionPalette.green,
                )
                activeChallenge != null -> {
                    Text(
                        text = "挑战编号 ${activeChallenge.challengeId.take(8)}… · 到期 " +
                            Formatters.shortDateTime(activeChallenge.expiresAt),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = card.codeInput,
                        onValueChange = { value ->
                            card.codeInput = value.filter { it.isDigit() }.take(6)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("一次性验证码", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !card.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { card.verifyCode() },
                            enabled = !card.busy && card.codeInput.length == 6,
                        ) {
                            Text("校验验证码", fontSize = 12.sp)
                        }
                        TextButton(onClick = { card.beginVerification() }, enabled = !card.busy) {
                            Text("重新发起验证", fontSize = 12.sp)
                        }
                    }
                }
                else -> {
                    Text(
                        text = "先发起验证，再从独立本机验证台取得演示码。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { card.beginVerification() }, enabled = !card.busy) {
                        Text("发起验证", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmRow(card: ActionCardState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !card.busy) { card.confirmed = !card.confirmed },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = card.confirmed,
            onCheckedChange = { card.confirmed = it },
            enabled = !card.busy,
        )
        Text(text = "我已核对本次参数，并明确确认操作", fontSize = 12.sp)
    }
}

@Composable
private fun ActionButtons(card: ActionCardState) {
    val operable = card.effectiveStatus in OPERABLE_STATUSES
    Column(modifier = Modifier.fillMaxWidth()) {
        if (operable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { card.confirmAndSubmit() },
                    enabled = card.canConfirm,
                    modifier = Modifier.weight(1f),
                ) {
                    if (card.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(text = card.primaryLabel, fontSize = 13.sp)
                    }
                }
                OutlinedButton(onClick = { card.cancelAction() }, enabled = !card.busy) {
                    Text("取消操作", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { card.lookup() }, enabled = !card.busy) {
                Text("查询最新状态", fontSize = 12.sp)
            }
            if (operable) {
                TextButton(onClick = { card.refreshRisk() }, enabled = !card.busy) {
                    Text("刷新风险条件", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MandateResultList(card: ActionCardState) {
    val mandates: List<MandateVo> = card.result?.data ?: return
    if (mandates.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "银行侧代扣（${mandates.size} 项）",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            mandates.forEach { mandate ->
                val amount = mandate.amountMinor?.let { Formatters.money(it) } ?: "金额未知"
                Text(
                    text = "${mandate.merchant} · $amount · " +
                        "${Formatters.billingIntervalLabel(mandate.billingInterval)} · " +
                        mandateStatusLabel(mandate.status),
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
