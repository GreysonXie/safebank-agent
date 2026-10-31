package cn.safebank.app.ui.screens.more

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.ChallengeVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 待确认操作的轻量状态机（转账页 / 代扣撤销 / 代扣查询共用）：
 *
 * 预览 → （needsRefresh）刷新风险 → （RED）创建挑战 → 校验验证码 → 授权 → 提交 → 查单 / 取消。
 *
 * 关键契约（docs/api-contract §6.2）：
 * - 授权与提交是两个独立请求，绝不合并；
 * - payloadHash 必须来自同一份预览；
 * - RED 动作必须带 verificationProofId；
 * - 提交后 status 为 PENDING/UNKNOWN 时只能查单，禁止新建动作。
 *
 * 所有方法都是「发起协程 + 中文提示」，不会抛异常给 UI。
 */
internal class PendingActionFlow(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
    private val onToast: (String) -> Unit,
) {
    var action by mutableStateOf<ActionVo?>(null)
        private set
    var risk by mutableStateOf<RiskDecisionVo?>(null)
        private set
    var receipt by mutableStateOf<ActionExecutionResultVo?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var statusLine by mutableStateOf("")
        private set
    var confirmed by mutableStateOf(false)
    var challenge by mutableStateOf<ChallengeVo?>(null)
        private set
    var challengeCode by mutableStateOf("")
    var challengeBusy by mutableStateOf(false)
        private set
    var verificationProofId by mutableStateOf<String?>(null)
        private set
    var verificationLine by mutableStateOf("")
        private set

    /** 以最新一次判定为准（刷新后的 risk 优先，其次预览里内嵌的 security）。 */
    val effectiveRisk: RiskDecisionVo? get() = risk ?: action?.security

    val riskLevel: String get() = effectiveRisk?.riskLevel.orEmpty()

    val isRed: Boolean get() = riskLevel.equals("RED", ignoreCase = true)

    val isTerminal: Boolean
        get() {
            val status = receipt?.status ?: return false
            return status in TERMINAL_ACTION_STATUSES
        }

    /** RED 且尚未授权时展示验证区。 */
    val showVerification: Boolean
        get() {
            val current = action ?: return false
            return isRed && current.status != "AUTHORIZED" && !isTerminal
        }

    /** 确认按钮启用条件（ui-spec §3.6）：勾选 + 有 decisionId + 未过期 + RED 已验证。 */
    val canConfirm: Boolean
        get() {
            val current = action ?: return false
            if (busy || isTerminal) return false
            if (current.status != "AWAITING_AUTH" && current.status != "AUTHORIZED") return false
            val security = effectiveRisk ?: return false
            if (security.decisionId.isNullOrBlank() || security.needsRefresh) return false
            if (!confirmed) return false
            if (security.isRed && current.status != "AUTHORIZED" && verificationProofId == null) return false
            return true
        }

    /** 载入新的预览动作（重新生成预览时调用，重置全部派生状态）。 */
    fun begin(newAction: ActionVo) {
        action = newAction
        risk = null
        receipt = null
        busy = false
        errorMessage = null
        confirmed = false
        challenge = null
        challengeCode = ""
        challengeBusy = false
        verificationProofId = null
        verificationLine = ""
        statusLine = actionNextStepText(newAction)
    }

    /** 关闭卡片（查询/撤销成功落地后由页面调用）。 */
    fun dismiss() {
        action = null
        risk = null
        receipt = null
        busy = false
        errorMessage = null
        confirmed = false
        challenge = null
        challengeCode = ""
        challengeBusy = false
        verificationProofId = null
        verificationLine = ""
        statusLine = ""
    }

    fun clearError() {
        errorMessage = null
    }

    private suspend fun ensureDecisionId(): String? {
        val current = action ?: return null
        val existing = effectiveRisk
        if (existing != null && !existing.needsRefresh && !existing.decisionId.isNullOrBlank()) {
            return existing.decisionId
        }
        val refreshed = repository.refreshRiskPreview(current.id).getOrNull() ?: return null
        risk = refreshed
        if (refreshed.needsRefresh) return null
        return refreshed.decisionId
    }

    /** 「刷新风险条件」：拿权威 decisionId（needsRefresh 恒 false）。 */
    fun refreshRisk() {
        val current = action ?: return
        if (busy) return
        busy = true
        errorMessage = null
        scope.launch {
            val result = repository.refreshRiskPreview(current.id)
            val updated = result.getOrNull()
            if (updated == null) {
                errorMessage = friendlyError(result.exceptionOrNull())
            } else {
                risk = updated
                statusLine = actionNextStepText(action)
                onToast("风险条件已刷新，请核对后确认。")
            }
            busy = false
        }
    }

    /** 「发起验证」（RED）：先刷新判定，再创建一次性验证挑战。 */
    fun startChallenge() {
        val current = action ?: return
        if (busy || challengeBusy) return
        challengeBusy = true
        errorMessage = null
        scope.launch {
            val decisionId = ensureDecisionId()
            if (decisionId == null) {
                errorMessage = "无法取得最新风险判定，请先刷新风险条件后重试。"
                challengeBusy = false
                return@launch
            }
            val result = repository.createChallenge(current.id, current.payloadHash, decisionId)
            val created = result.getOrNull()
            if (created == null) {
                errorMessage = friendlyError(result.exceptionOrNull())
            } else {
                challenge = created
                verificationLine = "挑战编号 ${shortId(created.challengeId)} · 到期 " +
                    "${Formatters.shortDateTime(created.expiresAt)}。请从独立验证台取得编码后输入。"
            }
            challengeBusy = false
        }
    }

    /** 「校验验证码」：拿到 verificationProofId。 */
    fun verifyChallengeCode() {
        val current = action ?: return
        val active = challenge
        if (active == null) {
            errorMessage = "请先点击「发起验证」生成一次性验证挑战。"
            return
        }
        val code = challengeCode.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            errorMessage = "请输入 6 位数字验证码。"
            return
        }
        if (challengeBusy) return
        challengeBusy = true
        errorMessage = null
        scope.launch {
            val result = repository.verifyChallenge(current.id, active.challengeId, code)
            val proof = result.getOrNull()
            if (proof == null) {
                errorMessage = friendlyError(result.exceptionOrNull())
            } else {
                verificationProofId = proof.verificationProofId
                verificationLine = "验证已通过。尚未授权或执行，请再次核对并勾选确认。"
                challengeCode = ""
                challenge = null
                confirmed = false
                onToast("验证已通过，请重新勾选确认后提交。")
            }
            challengeBusy = false
        }
    }

    /** 「确认并执行 / 确认并查询」：刷新判定 → 授权 → 提交。 */
    fun confirmAndSubmit() {
        val current = action ?: return
        if (busy) return
        if (isTerminal) {
            onToast("该操作已有回执，请用「查询最新状态」查单。")
            return
        }
        if (!confirmed) {
            onToast("请先勾选「我已核对本次参数，并明确确认操作」。")
            return
        }
        busy = true
        errorMessage = null
        scope.launch {
            val decisionId = ensureDecisionId()
            if (decisionId == null) {
                errorMessage = "无法取得最新风险判定（可能已过期），请刷新风险条件后重试。"
                busy = false
                return@launch
            }
            val security = effectiveRisk
            if (security != null && security.isRed && current.status != "AUTHORIZED" && verificationProofId == null) {
                errorMessage = "红色风险操作需要先完成一次性验证码校验后再确认。"
                busy = false
                return@launch
            }
            val authResult = repository.authorize(
                id = current.id,
                payloadHash = current.payloadHash,
                decisionId = decisionId,
                verificationProofId = verificationProofId,
                userConfirmed = true,
            )
            val authorized = authResult.getOrNull()
            if (authorized == null) {
                errorMessage = friendlyError(authResult.exceptionOrNull())
                busy = false
                return@launch
            }
            action = authorized
            statusLine = actionNextStepText(authorized)
            val submitResult = repository.submit(current.id)
            val submitted = submitResult.getOrNull()
            if (submitted == null) {
                errorMessage = friendlyError(submitResult.exceptionOrNull())
                busy = false
                return@launch
            }
            receipt = submitted
            action = submitted.toActionVo()
            statusLine = receiptMessage(submitted)
            val toastText = when (submitted.status) {
                "VERIFIED_SUCCESS" -> "操作已完成。"
                "VERIFIED_FAILED" -> "操作未完成：${errorCodeLabel(submitted.errorCode) ?: "请查看详情"}"
                "PENDING", "UNKNOWN" ->
                    "结果未定，请用「查询最新状态」按原操作编号查单，不要重新创建操作。"

                else -> receiptMessage(submitted)
            }
            onToast(toastText)
            busy = false
        }
    }

    /** 「查询最新状态」：只按原 action id 查单，不新建动作。 */
    fun lookup() {
        val current = action ?: return
        if (busy) return
        busy = true
        errorMessage = null
        scope.launch {
            val result = repository.action(current.id)
            val latest = result.getOrNull()
            if (latest == null) {
                errorMessage = friendlyError(result.exceptionOrNull())
            } else {
                receipt = latest
                action = latest.toActionVo()
                statusLine = receiptMessage(latest)
                onToast("查单结果：${Formatters.actionStatusLabel(latest.status)}")
            }
            busy = false
        }
    }

    /** 「取消操作」（仅 AWAITING_AUTH / AUTHORIZED 允许）。 */
    fun cancel() {
        val current = action ?: return
        if (busy) return
        busy = true
        errorMessage = null
        scope.launch {
            val result = repository.cancel(current.id)
            val cancelled = result.getOrNull()
            if (cancelled == null) {
                errorMessage = friendlyError(result.exceptionOrNull())
            } else {
                action = cancelled
                statusLine = "本操作已取消。"
                onToast("操作已取消。")
            }
            busy = false
        }
    }

    private companion object {
        val TERMINAL_ACTION_STATUSES = setOf(
            "VERIFIED_SUCCESS",
            "VERIFIED_FAILED",
            "PENDING",
            "UNKNOWN",
            "CANCELLED",
        )
    }
}
