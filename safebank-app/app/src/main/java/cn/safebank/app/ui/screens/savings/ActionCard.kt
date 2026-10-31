package cn.safebank.app.ui.screens.savings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.network.ApiException
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.ActionVo
import cn.safebank.app.data.model.ChallengeVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.model.VerificationProofVo
import cn.safebank.app.data.util.Formatters
import kotlinx.coroutines.launch

private val FINAL_ACTION_STATUSES = setOf("VERIFIED_SUCCESS", "VERIFIED_FAILED", "CANCELLED")

/**
 * 待确认操作卡（储蓄任务页私有版本）。
 *
 * 覆盖契约 §6.2 的两步授权：needsRefresh → refreshRiskPreview；RED → 验证挑战 + 验证码；
 * authorize → submit；结果未知时只允许查单，不新建动作。所有失败都用中文横幅提示，不抛异常到 UI。
 *
 * @param onChanged 动作状态被本地改变（取消）后回调，便于父级刷新。
 * @param onSubmitted 提交/查单拿到执行回执后回调（QUERY_MANDATES 的 data 只会在首次 submit 出现）。
 */
@Composable
internal fun ActionConfirmCard(
    action: ActionVo,
    repository: SafeBankRepository,
    modifier: Modifier = Modifier,
    onChanged: (ActionVo) -> Unit = {},
    onSubmitted: (ActionExecutionResultVo) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var current by remember(action) { mutableStateOf(action) }
    var risk by remember(action) { mutableStateOf(action.security) }
    var receipt by remember(action) { mutableStateOf<ActionExecutionResultVo?>(null) }
    var error by remember(action) { mutableStateOf<String?>(null) }
    var notice by remember(action) { mutableStateOf<String?>(null) }
    var busy by remember(action) { mutableStateOf(false) }
    var confirmed by remember(action) { mutableStateOf(false) }
    var challenge by remember(action) { mutableStateOf<ChallengeVo?>(null) }
    var proof by remember(action) { mutableStateOf<VerificationProofVo?>(null) }
    var otp by remember(action) { mutableStateOf("") }
    var verificationExpanded by remember(action) { mutableStateOf(false) }
    var detailsExpanded by remember(action) { mutableStateOf(false) }

    val isFinal = current.status in FINAL_ACTION_STATUSES
    val riskLevel = risk?.riskLevel ?: current.security?.riskLevel
    val isRed = riskLevel.equals("RED", ignoreCase = true)
    val canCancel = current.status == "AWAITING_AUTH" || current.status == "AUTHORIZED"
    val remainingSeconds = Formatters.remainingSeconds(current.expiresAt)

    /** 统一动作失败处理：中文提示 + 若风险错误带最新 security 则重绘风险卡并作废旧验证。 */
    fun handleActionError(t: Throwable) {
        error = failureText(t)
        val api = t as? ApiException
        val latestSecurity = api?.security
        if (latestSecurity != null) {
            risk = latestSecurity
            confirmed = false
            proof = null
        }
    }

    fun handleReceipt(result: ActionExecutionResultVo) {
        receipt = result
        current = result.toActionVo()
        onChanged(current)
        onSubmitted(result)
        notice = when (result.status) {
            "UNKNOWN" ->
                "提交结果未知：请点击「查询最新状态」按原操作编号查单，不要新建相同操作。"
            "PENDING" ->
                "银行已受理、处理中：请稍后点击「查询最新状态」核对，不要重复提交。"
            else -> null
        }
    }

    fun submitCurrent() {
        busy = true
        error = null
        scope.launch {
            val result = repository.submit(current.id)
            result.onFailure { t -> handleActionError(t) }
            result.getOrNull()?.let { handleReceipt(it) }
            busy = false
        }
    }

    /**
     * 确认并提交：needsRefresh/无 decisionId 时先刷新风险；RED 且未验证时要求先完成验证；
     * 已 AUTHORIZED 时直接提交，避免重复授权。
     */
    fun authorizeAndSubmit() {
        busy = true
        error = null
        notice = null
        scope.launch {
            var decision = risk
            if (decision == null || decision.needsRefresh || decision.decisionId.isNullOrBlank()) {
                val refreshed = repository.refreshRiskPreview(current.id)
                refreshed.onFailure { t -> handleActionError(t) }
                decision = refreshed.getOrNull()
                decision?.let { risk = it }
            }
            val activeDecision: RiskDecisionVo? = decision
            if (activeDecision == null) {
                busy = false
                return@launch
            }
            if (activeDecision.isRed && proof == null) {
                verificationExpanded = true
                error = "该操作风险等级为红色（RED），需要先完成一次性验证码验证，再勾选确认。"
                busy = false
                return@launch
            }
            val decisionId = activeDecision.decisionId
            if (decisionId.isNullOrBlank()) {
                error = "风险判定缺少 decisionId，请先点击「刷新风险条件」后重试。"
                busy = false
                return@launch
            }
            val authorization = repository.authorize(
                id = current.id,
                payloadHash = current.payloadHash,
                decisionId = decisionId,
                verificationProofId = proof?.verificationProofId,
                userConfirmed = true,
            )
            authorization.onFailure { t -> handleActionError(t) }
            val authorized = authorization.getOrNull()
            if (authorized == null) {
                busy = false
                return@launch
            }
            current = authorized
            risk = authorized.security ?: activeDecision
            notice = "授权已记录，正在提交银行…"
            val result = repository.submit(current.id)
            result.onFailure { t -> handleActionError(t) }
            result.getOrNull()?.let { handleReceipt(it) }
            busy = false
        }
    }

    val primaryAction: () -> Unit = {
        if (!busy && !isFinal) {
            if (current.status == "AUTHORIZED") submitCurrent() else authorizeAndSubmit()
        }
    }

    val refreshRisk: () -> Unit = {
        if (!busy) {
            busy = true
            error = null
            notice = null
            scope.launch {
                val result = repository.refreshRiskPreview(current.id)
                result.onFailure { t -> handleActionError(t) }
                result.getOrNull()?.let {
                    risk = it
                    proof = null
                    confirmed = false
                    notice = "风险条件已刷新（${it.riskLevel}）。请重新核对后勾选确认。"
                }
                busy = false
            }
        }
    }

    val lookup: () -> Unit = {
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                val result = repository.action(current.id)
                result.onFailure { t -> handleActionError(t) }
                result.getOrNull()?.let { handleReceipt(it) }
                busy = false
            }
        }
    }

    val cancelAction: () -> Unit = {
        if (!busy && canCancel) {
            busy = true
            error = null
            scope.launch {
                val result = repository.cancel(current.id)
                result.onFailure { t -> handleActionError(t) }
                result.getOrNull()?.let {
                    current = it
                    notice = "本操作已取消（预览/授权已作废）。"
                    onChanged(it)
                }
                busy = false
            }
        }
    }

    val startVerification: () -> Unit = {
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                var decision = risk
                if (decision == null || decision.needsRefresh || decision.decisionId.isNullOrBlank()) {
                    val refreshed = repository.refreshRiskPreview(current.id)
                    refreshed.onFailure { t -> handleActionError(t) }
                    decision = refreshed.getOrNull()
                    decision?.let { risk = it }
                }
                val activeDecision: RiskDecisionVo? = decision
                if (activeDecision == null) {
                    busy = false
                    return@launch
                }
                if (!activeDecision.isRed) {
                    error = "当前风险等级不是红色（RED），无需发起验证。"
                    busy = false
                    return@launch
                }
                val decisionId = activeDecision.decisionId
                if (decisionId.isNullOrBlank()) {
                    error = "风险判定缺少 decisionId，请先刷新风险条件。"
                    busy = false
                    return@launch
                }
                val result = repository.createChallenge(
                    id = current.id,
                    payloadHash = current.payloadHash,
                    decisionId = decisionId,
                )
                result.onFailure { t ->
                    handleActionError(t)
                    error = verificationFailureText(t)
                }
                result.getOrNull()?.let {
                    challenge = it
                    proof = null
                    verificationExpanded = true
                }
                busy = false
            }
        }
    }

    val verifyCode: () -> Unit = {
        val activeChallenge = challenge
        if (!busy && activeChallenge != null && otp.trim().length == 6) {
            busy = true
            error = null
            scope.launch {
                val result = repository.verifyChallenge(
                    id = current.id,
                    challengeId = activeChallenge.challengeId,
                    code = otp.trim(),
                )
                result.onFailure { t -> handleActionError(t) }
                result.getOrNull()?.let {
                    proof = it
                    confirmed = false
                    otp = ""
                    notice = "验证已通过。尚未授权或执行，请再次核对并勾选确认。"
                }
                busy = false
            }
        }
    }

    val primaryLabel = when {
        current.status == "AUTHORIZED" -> "提交已授权操作"
        current.kind == "QUERY_MANDATES" -> "确认并查询"
        else -> "确认并执行"
    }
    val canConfirm = !isFinal && !busy && confirmed &&
        (!isRed || proof != null || current.status == "AUTHORIZED")

    // ------------------------------------------------------------ 卡片主体
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SavingsPalette.Surface, RoundedCornerShape(9.dp))
            .border(1.dp, SavingsPalette.Line, RoundedCornerShape(9.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionTitle(current.kind),
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = SavingsPalette.TextPrimary,
            )
            ActionStatusBadge(current.status)
        }
        Spacer(Modifier.height(8.dp))

        // 下一步提示条（左侧 3dp 竖条语义）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SavingsPalette.NextStepBg, RoundedCornerShape(4.dp)),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .background(SavingsPalette.NextStepBorder),
            )
            Text(
                text = nextStepText(current.status),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = SavingsPalette.TextSecondary,
            )
        }

        if (notice != null) {
            Spacer(Modifier.height(10.dp))
            Banner(notice!!, if (proof != null) BannerTone.SUCCESS else BannerTone.INFO)
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            ErrorBanner(error!!)
        }

        Spacer(Modifier.height(12.dp))
        ActionParameterRows(current)

        if (!isFinal && remainingSeconds != null) {
            KeyValueRow("预览有效期", "剩余 ${remainingSeconds.coerceAtLeast(0)} 秒")
        }

        // 风险分层卡
        val shownRisk = risk ?: current.security
        if (shownRisk != null) {
            Spacer(Modifier.height(12.dp))
            RiskLevelCard(shownRisk)
        }

        // 技术详情折叠
        Spacer(Modifier.height(8.dp))
        CollapseHeader("查看操作详情", detailsExpanded) { detailsExpanded = !detailsExpanded }
        if (detailsExpanded) {
            KeyValueRow("操作编号", current.id)
            KeyValueRow("操作类型", current.kind)
            if (!current.receiptId.isNullOrBlank()) KeyValueRow("回执编号", current.receiptId!!)
            errorCodeLabel(current.errorCode)?.let { KeyValueRow("状态说明", it) }
            KeyValueRow("参数哈希", if (current.payloadHash.length > 16) current.payloadHash.take(16) + "…" else current.payloadHash)
            KeyValueRow("当前状态", Formatters.actionStatusLabel(current.status))
        }

        // RED 验证区
        if (isRed && !isFinal) {
            Spacer(Modifier.height(12.dp))
            VerificationSection(
                expanded = verificationExpanded,
                challenge = challenge,
                proof = proof,
                otp = otp,
                busy = busy,
                onOtpChange = { value ->
                    if (value.length <= 6 && value.all { it.isDigit() }) otp = value
                },
                onStart = startVerification,
                onVerify = verifyCode,
            )
        }

        if (!isFinal) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = SavingsPalette.Line)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = confirmed,
                        enabled = !busy,
                        role = Role.Checkbox,
                        onValueChange = { confirmed = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = confirmed, onCheckedChange = null, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "我已核对本次参数，并明确确认操作",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = SavingsPalette.TextPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = refreshRisk, enabled = !busy) {
                    Text("刷新风险条件", fontSize = 12.sp)
                }
                Button(onClick = primaryAction, enabled = canConfirm) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(primaryLabel, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canCancel) {
                    OutlinedButton(onClick = cancelAction, enabled = !busy) {
                        Text("取消操作", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = lookup, enabled = !busy) {
                    Text("查询最新状态", fontSize = 12.sp)
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = lookup, enabled = !busy) {
                    Text("查询最新状态", fontSize = 12.sp)
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) {
                        Text("收起卡片", fontSize = 12.sp)
                    }
                }
            }
        }

        // 执行回执
        val shownReceipt = receipt
        if (shownReceipt != null) {
            Spacer(Modifier.height(12.dp))
            ReceiptBlock(shownReceipt)
        } else if (isFinal) {
            Spacer(Modifier.height(12.dp))
            Banner(
                text = receiptExplanation(current.kind, current.status, hasData = false) +
                    (current.receiptId?.let { "\n回执编号：$it" } ?: "") +
                    (errorCodeLabel(current.errorCode)?.let { "\n状态说明：$it" } ?: ""),
                tone = if (current.status == "VERIFIED_SUCCESS") BannerTone.SUCCESS else BannerTone.WARNING,
            )
        }

        // 没有回执但已提交（父级回填的终态动作）
        if (isFinal && current.kind == "QUERY_MANDATES" && error != null) {
            Spacer(Modifier.height(8.dp))
            HintText("查询回执只会在首次提交时返回代扣数据；重复查询需重新确认查询范围。")
        }
    }
}

@Composable
private fun ActionParameterRows(action: ActionVo) {
    when (action.kind) {
        "TRANSFER" -> {
            KeyValueRow("来源账户", action.sourceId ?: "—")
            KeyValueRow("收款对象", action.recipientLabel ?: action.recipientId ?: "—")
            KeyValueRow("金额", action.amountMinor?.let { Formatters.money(it) } ?: "—")
            action.feeMinor?.let { KeyValueRow("手续费", Formatters.money(it)) }
            action.scheduleId?.let {
                KeyValueRow("定时计划", it)
                action.occurrenceDate?.let { date -> KeyValueRow("执行日期", date) }
            }
        }
        "REVOKE" -> {
            KeyValueRow("代扣商户", action.merchant ?: "—")
            action.chargeAmountMinor?.let { KeyValueRow("单期金额", Formatters.money(it)) }
            KeyValueRow("扣费周期", Formatters.billingIntervalLabel(action.billingInterval))
            action.estimatedMonthlySavingMinor?.let {
                KeyValueRow("预计月度节省", Formatters.money(it))
            }
            KeyValueRow("合同状态", if (action.contractStatus == "UNKNOWN") "未知" else action.contractStatus ?: "未知")
            action.scope?.let { KeyValueRow("影响", it) }
        }
        "SCHEDULE" -> {
            KeyValueRow("来源账户", action.sourceId ?: "—")
            KeyValueRow("储蓄目标", action.recipientId ?: "—")
            KeyValueRow("每期金额", action.amountMinor?.let { Formatters.money(it) } ?: "—")
            KeyValueRow(
                "执行规则",
                "每月 ${action.dayOfMonth ?: "—"} 日 · ${action.timezone ?: "Asia/Shanghai"}",
            )
            KeyValueRow("首次日期", action.firstDate ?: "—")
            KeyValueRow("结束日期", action.endDate ?: "—")
            action.insufficientFunds?.let { KeyValueRow("资金不足策略", it) }
        }
        "QUERY_MANDATES" -> {
            KeyValueRow("查询范围", "本人银行代扣（OWN_BANK_MANDATES）")
            HintText("查询本身不会转账、不会记账。")
        }
        else -> {
            action.amountMinor?.let { KeyValueRow("金额", Formatters.money(it)) }
            action.merchant?.let { KeyValueRow("对象", it) }
        }
    }
}

@Composable
private fun RiskLevelCard(risk: RiskDecisionVo) {
    val (background, foreground, borderColor) = when {
        risk.isRed -> Triple(SavingsPalette.RiskRedBg, SavingsPalette.RiskRedFg, SavingsPalette.RiskRedBorder)
        risk.isYellow -> Triple(SavingsPalette.RiskYellowBg, SavingsPalette.RiskYellowFg, SavingsPalette.RiskYellowBg)
        else -> Triple(SavingsPalette.RiskGreenBg, SavingsPalette.RiskGreenFg, SavingsPalette.RiskGreenBg)
    }
    val title = when {
        risk.isRed -> "红色 · 需要验证后确认"
        risk.isYellow -> "黄色 · 需要明确确认"
        else -> "绿色 · 可自动查询"
    }
    val body = if (risk.isRed) {
        "本次预计日累计 ${Formatters.money(risk.projectedMinor)}，超过 ${Formatters.money(100000)}，需补充验证。原因：${reasonCodeLabel(risk.reasonCode)}"
    } else {
        "核对本次操作范围、对象与参数后确认。原因：${reasonCodeLabel(risk.reasonCode)}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = foreground)
        Spacer(Modifier.height(4.dp))
        Text(text = body, fontSize = 12.sp, color = foreground)
        if (risk.verificationMode == "LOCAL_DEMO_ONLY") {
            Spacer(Modifier.height(4.dp))
            Text(text = "本地合成验证演示", fontSize = 11.sp, color = foreground)
        }
    }
}

@Composable
private fun VerificationSection(
    expanded: Boolean,
    challenge: ChallengeVo?,
    proof: VerificationProofVo?,
    otp: String,
    busy: Boolean,
    onOtpChange: (String) -> Unit,
    onStart: () -> Unit,
    onVerify: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SavingsPalette.SoftBlue, RoundedCornerShape(8.dp))
            .border(1.dp, SavingsPalette.Line, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "红色风险验证",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SavingsPalette.RiskRedFg,
        )
        Spacer(Modifier.height(4.dp))
        if (proof != null) {
            Text(
                text = "验证已通过。尚未授权或执行，请再次核对并勾选确认。",
                fontSize = 12.sp,
                color = SavingsPalette.RiskGreenFg,
            )
        } else {
            Text(
                text = if (expanded) "先发起验证，再从独立本机验证台取得 6 位演示码。" else "红色等级操作需要一次性验证码；请先发起验证。",
                fontSize = 12.sp,
                color = SavingsPalette.TextSecondary,
            )
            if (challenge != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "挑战编号 ${challenge.challengeId} · 到期 ${Formatters.shortDateTime(challenge.expiresAt)}",
                    fontSize = 11.sp,
                    color = SavingsPalette.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                SavingsTextField(
                    value = otp,
                    onValueChange = onOtpChange,
                    label = "一次性验证码",
                    placeholder = "6 位数字",
                    helper = "验证码由独立验证台提供，App 不会展示验证码。",
                    keyboardType = KeyboardType.NumberPassword,
                    enabled = !busy,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onVerify, enabled = !busy && otp.length == 6) {
                    Text("校验验证码", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onStart, enabled = !busy) {
                Text("发起验证", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReceiptBlock(receipt: ActionExecutionResultVo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SavingsPalette.SoftBlue, RoundedCornerShape(8.dp))
            .border(1.dp, SavingsPalette.Line, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "执行回执",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SavingsPalette.TextPrimary,
            )
            ActionStatusBadge(receipt.status)
        }
        Spacer(Modifier.height(6.dp))
        KeyValueRow("回执编号", receipt.receiptId ?: "—")
        errorCodeLabel(receipt.errorCode)?.let { KeyValueRow("状态说明", it) }
        if (!receipt.observedAt.isNullOrBlank()) {
            KeyValueRow("查单时间", Formatters.shortDateTime(receipt.observedAt))
        }
        if (!receipt.queriedAt.isNullOrBlank()) {
            KeyValueRow("查询时间", Formatters.shortDateTime(receipt.queriedAt))
        }
        Text(
            text = receiptExplanation(receipt.kind, receipt.status, receipt.data != null),
            fontSize = 12.sp,
            color = SavingsPalette.TextSecondary,
        )
    }
}
