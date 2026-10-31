package cn.safebank.app.ui.screens.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.safebank.app.data.model.ActionExecutionResultVo
import cn.safebank.app.data.model.RiskDecisionVo
import cn.safebank.app.data.util.Formatters

/**
 * 待确认操作卡（ui-spec §3.6 固定顺序）：
 * 状态行 → 下一步提示 → 错误条 → 参数列表 → 风险分层 → RED 验证区 → 确认勾选 → 按钮行 → 回执。
 *
 * 转账 / 代扣撤销 / 代扣查询三个流程共用；页面只在回执落地时做各自副作用。
 */
@Composable
internal fun PendingActionCard(
    flow: PendingActionFlow,
    modifier: Modifier = Modifier,
    sourceLabel: String? = null,
) {
    val action = flow.action ?: return
    val security = flow.effectiveRisk
    val receipt = flow.receipt

    SectionCard(
        title = actionKindTitle(action.kind),
        subtitle = "预览有效期 5 分钟；「授权」与「提交」是两步，提交后请以查单结果为准。",
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StatusBadge(
                text = Formatters.actionStatusLabel(action.status),
                tone = actionStatusTone(action.status),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "操作编号 ${action.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        NextStepLine(flow.statusLine.ifBlank { actionNextStepText(action) })

        val errorText = flow.errorMessage
        if (errorText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoBanner(text = errorText, tone = Tone.Danger)
        }

        Spacer(modifier = Modifier.height(8.dp))
        when (action.kind) {
            "TRANSFER" -> {
                KeyValueRow("来源账户", sourceLabel ?: action.sourceId.orEmpty())
                KeyValueRow("收款对象", action.recipientLabel ?: action.recipientId.orEmpty())
                KeyValueRow("转账金额", moneyOrDash(action.amountMinor))
                KeyValueRow("手续费", moneyOrDash(action.feeMinor))
                KeyValueRow("失效时间", expiryLine(action.expiresAt))
            }

            "REVOKE" -> {
                KeyValueRow("代扣商户", action.merchant.orEmpty())
                KeyValueRow("单期扣费", moneyOrDash(action.chargeAmountMinor))
                KeyValueRow("计费周期", intervalLabel(action.billingInterval))
                KeyValueRow(
                    "预计月度节省",
                    action.estimatedMonthlySavingMinor?.let { Formatters.money(it) }
                        ?: "仅按月的代扣提供预计节省",
                )
                KeyValueRow("影响范围", action.scope ?: "仅撤销银行侧代扣授权，商户合同状态未知")
                KeyValueRow("商户合同状态", "未知（撤销只影响银行侧授权）")
                KeyValueRow("失效时间", expiryLine(action.expiresAt))
            }

            "QUERY_MANDATES" -> {
                KeyValueRow("查询范围", "本人银行侧代扣授权（OWN_BANK_MANDATES）")
                KeyValueRow("失效时间", expiryLine(action.expiresAt))
                Spacer(modifier = Modifier.height(4.dp))
                InfoBanner(
                    text = "查询本身不转账、不记账；回执只在本次提交返回，重复查询需要再次确认范围。",
                    tone = Tone.Neutral,
                )
            }

            "SCHEDULE" -> {
                KeyValueRow("来源账户", sourceLabel ?: action.sourceId.orEmpty())
                KeyValueRow("储蓄目标", action.recipientId.orEmpty())
                KeyValueRow("每月金额", moneyOrDash(action.amountMinor))
                KeyValueRow(
                    "执行规则",
                    "每月 ${action.dayOfMonth ?: "?"} 日 · ${action.timezone ?: "Asia/Shanghai"}",
                )
                KeyValueRow("首次日期", action.firstDate.orEmpty().ifBlank { "—" })
                KeyValueRow("结束日期", action.endDate.orEmpty().ifBlank { "—" })
                KeyValueRow("资金不足", action.insufficientFunds ?: "本次失败，不补扣")
                KeyValueRow("失效时间", expiryLine(action.expiresAt))
            }

            else -> KeyValueRow("操作类型", action.kind)
        }

        if (security != null) {
            Spacer(modifier = Modifier.height(8.dp))
            RiskLayer(security)
        }

        if (flow.showVerification) {
            Spacer(modifier = Modifier.height(8.dp))
            VerificationBlock(flow)
        }

        if (action.status == "AWAITING_AUTH" || action.status == "AUTHORIZED") {
            Spacer(modifier = Modifier.height(6.dp))
            ToggleRow(
                checked = flow.confirmed,
                onCheckedChange = { flow.confirmed = it },
                text = "我已核对本次参数，并明确确认操作",
                enabled = !flow.busy,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    text = "刷新风险条件",
                    onClick = { flow.refreshRisk() },
                    modifier = Modifier.weight(1f),
                    enabled = !flow.busy && !flow.isTerminal,
                    loading = flow.busy,
                )
                PrimaryActionButton(
                    text = if (action.kind == "QUERY_MANDATES") "确认并查询" else "确认并执行",
                    onClick = { flow.confirmAndSubmit() },
                    modifier = Modifier.weight(1f),
                    enabled = flow.canConfirm,
                    loading = flow.busy,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    text = "查询最新状态",
                    onClick = { flow.lookup() },
                    modifier = Modifier.weight(1f),
                    enabled = !flow.busy,
                )
                SecondaryActionButton(
                    text = "取消操作",
                    onClick = { flow.cancel() },
                    modifier = Modifier.weight(1f),
                    enabled = !flow.busy && (action.status == "AWAITING_AUTH" || action.status == "AUTHORIZED"),
                )
            }
        }

        if (receipt != null) {
            Spacer(modifier = Modifier.height(8.dp))
            ToolkitDivider()
            ReceiptBlock(receipt)
        }
    }
}

@Composable
private fun NextStepLine(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFFF5F8FD),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF334155),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RiskLayer(risk: RiskDecisionVo) {
    val colors = toneColors(riskTone(risk.riskLevel))
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = colors.bg) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = riskLevelLabel(risk.riskLevel),
                style = MaterialTheme.typography.titleSmall,
                color = colors.fg,
            )
            Text(
                text = riskExplanation(risk),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fg,
                modifier = Modifier.padding(top = 4.dp),
            )
            val reason = reasonCodeLabel(risk.reasonCode)
            if (reason != null) {
                Text(
                    text = "判定原因：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fg,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "当日已占用 ${moneyOrDash(risk.effectiveMinor)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.fg,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (risk.isRed && risk.verificationMode == "LOCAL_DEMO_ONLY") {
                Text(
                    text = "本地合成验证演示",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fg,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun VerificationBlock(flow: PendingActionFlow) {
    val colors = toneColors(Tone.Warning)
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = colors.bg) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "红色风险 · 补充验证",
                style = MaterialTheme.typography.titleSmall,
                color = colors.fg,
            )
            Text(
                text = "先发起验证，再从独立本机验证台取得 6 位演示码；校验通过后需要重新勾选确认。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fg,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (flow.verificationLine.isNotBlank()) {
                Text(
                    text = flow.verificationLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fg,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val proofId = flow.verificationProofId
            if (proofId != null) {
                Text(
                    text = "已取得验证凭据：${shortId(proofId)}（尚未授权或执行）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fg,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryActionButton(
                text = "发起验证",
                onClick = { flow.startChallenge() },
                enabled = !flow.challengeBusy && !flow.busy,
                loading = flow.challengeBusy,
            )
            if (flow.challenge != null) {
                Spacer(modifier = Modifier.height(8.dp))
                FormField(
                    label = "一次性验证码",
                    value = flow.challengeCode,
                    onValueChange = { input -> flow.challengeCode = input.filter { it.isDigit() }.take(6) },
                    placeholder = "6 位数字",
                    keyboardType = KeyboardType.Number,
                    hint = "演示环境：从独立验证台读取编码（App 内不提供取码入口）",
                )
                PrimaryActionButton(
                    text = "校验验证码",
                    onClick = { flow.verifyChallengeCode() },
                    enabled = flow.challengeCode.length == 6 && !flow.challengeBusy,
                    loading = flow.challengeBusy,
                )
            }
        }
    }
}

@Composable
private fun ReceiptBlock(receipt: ActionExecutionResultVo) {
    Text(
        text = "执行回执",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    KeyValueRow("最终状态", Formatters.actionStatusLabel(receipt.status))
    KeyValueRow("回执编号", receipt.receiptId ?: "—")
    val errorLabel = errorCodeLabel(receipt.errorCode)
    if (errorLabel != null) {
        KeyValueRow("失败原因", errorLabel)
    }
    receipt.observedAt?.let { observed ->
        KeyValueRow("查单时间", Formatters.shortDateTime(observed))
    }
    val data = receipt.data
    if (receipt.kind == "QUERY_MANDATES" && data != null) {
        KeyValueRow("查询结果", "共 ${data.size} 条银行侧代扣授权")
    }
    Spacer(modifier = Modifier.height(6.dp))
    InfoBanner(
        text = receiptMessage(receipt),
        tone = if (receipt.status == "VERIFIED_SUCCESS") Tone.Success else Tone.Warning,
    )
    if (receipt.status == "UNKNOWN" || receipt.status == "PENDING") {
        Spacer(modifier = Modifier.height(6.dp))
        InfoBanner(
            text = "结果未定：只能按原操作编号查单，禁止重新创建相同操作（可能造成重复扣款）。",
            tone = Tone.Danger,
        )
    }
    if (receipt.status == "VERIFIED_FAILED") {
        Spacer(modifier = Modifier.height(6.dp))
        InfoBanner(text = "明确失败：本操作未完成；如需继续，请重新生成预览。", tone = Tone.Danger)
    }
}
