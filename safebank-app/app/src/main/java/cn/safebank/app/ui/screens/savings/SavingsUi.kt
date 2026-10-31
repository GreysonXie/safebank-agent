package cn.safebank.app.ui.screens.savings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.data.model.ScheduleVo
import cn.safebank.app.data.util.Formatters
import java.time.LocalDate
import java.time.ZoneId

/**
 * 储蓄任务页私有设计令牌与小组件。
 *
 * 说明：ui/components 目录由 UI 基础负责人并行维护，储蓄任务负责人不修改该目录，
 * 因此这里的组件全部是本包私有（internal）实现，不依赖其它页面的组件签名。
 * 颜色照 docs/ui-design-spec.md §1.2 / §6.1 的最终生效值。
 */
internal object SavingsPalette {
    val BrandBlue = Color(0xFF3A5ECF)
    val DeepBlue = Color(0xFF1A4F99)
    val BrandRed = Color(0xFFD80C18)
    val PageBackground = Color(0xFFF5F7FA)
    val Surface = Color(0xFFFFFFFF)
    val Line = Color(0xFFE2E8F0)
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF526173)
    val SurfaceVariant = Color(0xFFF1F5F9)
    val SoftBlue = Color(0xFFF3F6FF)

    val AwaitingBg = Color(0xFFFFF3D6)
    val AwaitingFg = Color(0xFF785000)
    val AuthorizedBg = Color(0xFFE9EDFF)
    val AuthorizedFg = Color(0xFF354B9A)
    val SuccessBg = Color(0xFFE5F5EE)
    val SuccessFg = Color(0xFF176345)
    val FailedBg = Color(0xFFFFE8E8)
    val FailedFg = Color(0xFF9C3030)
    val CancelledBg = Color(0xFFEEF1F5)
    val CancelledFg = Color(0xFF475569)
    val InfoBg = Color(0xFFEDF2FF)
    val InfoFg = Color(0xFF1A4F99)

    val ErrorBg = Color(0xFFFFF0F2)
    val ErrorFg = Color(0xFFA22237)

    val RiskRedBg = Color(0xFFFFF5F5)
    val RiskRedFg = Color(0xFF8E2635)
    val RiskRedBorder = Color(0xFFE9B7BD)
    val RiskYellowBg = Color(0xFFFFF3D6)
    val RiskYellowFg = Color(0xFF785000)
    val RiskGreenBg = Color(0xFFE5F5EE)
    val RiskGreenFg = Color(0xFF176345)

    val NextStepBg = Color(0xFFF5F8FD)
    val NextStepBorder = Color(0xFFB5C7E8)
}

internal enum class BadgeTone { DEFAULT, AWAITING, AUTHORIZED, SUCCESS, FAILED, CANCELLED }

internal enum class BannerTone { INFO, SUCCESS, WARNING, ERROR }

// ---------------------------------------------------------------- 通用小组件

/** 白底 1dp 描边面板（对齐 ui-design-spec §1.7：无投影、无 tonal 染色）。 */
@Composable
internal fun SectionPanel(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SavingsPalette.Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SavingsPalette.Line, RoundedCornerShape(10.dp))
            .padding(16.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = SavingsPalette.TextPrimary,
            )
        }
        if (subtitle != null) {
            if (title != null) Spacer(Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 12.sp, color = SavingsPalette.TextSecondary)
        }
        if (title != null || subtitle != null) Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun StatusBadge(text: String, tone: BadgeTone) {
    val (background, foreground) = when (tone) {
        BadgeTone.DEFAULT -> SavingsPalette.InfoBg to SavingsPalette.InfoFg
        BadgeTone.AWAITING -> SavingsPalette.AwaitingBg to SavingsPalette.AwaitingFg
        BadgeTone.AUTHORIZED -> SavingsPalette.AuthorizedBg to SavingsPalette.AuthorizedFg
        BadgeTone.SUCCESS -> SavingsPalette.SuccessBg to SavingsPalette.SuccessFg
        BadgeTone.FAILED -> SavingsPalette.FailedBg to SavingsPalette.FailedFg
        BadgeTone.CANCELLED -> SavingsPalette.CancelledBg to SavingsPalette.CancelledFg
    }
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = 12.sp, color = foreground)
    }
}

internal fun statusTone(status: String?): BadgeTone = when (status) {
    "AWAITING_AUTH", "PENDING", "UNKNOWN" -> BadgeTone.AWAITING
    "AUTHORIZED" -> BadgeTone.AUTHORIZED
    "VERIFIED_SUCCESS" -> BadgeTone.SUCCESS
    "VERIFIED_FAILED" -> BadgeTone.FAILED
    "CANCELLED" -> BadgeTone.CANCELLED
    else -> BadgeTone.DEFAULT
}

@Composable
internal fun ActionStatusBadge(status: String?) {
    StatusBadge(Formatters.actionStatusLabel(status), statusTone(status))
}

@Composable
internal fun WorkflowStatusBadge(state: String?) {
    StatusBadge(Formatters.workflowStateLabel(state), statusTone(state))
}

@Composable
internal fun Banner(text: String, tone: BannerTone = BannerTone.INFO) {
    val (background, foreground) = when (tone) {
        BannerTone.INFO -> SavingsPalette.InfoBg to SavingsPalette.InfoFg
        BannerTone.SUCCESS -> SavingsPalette.SuccessBg to SavingsPalette.SuccessFg
        BannerTone.WARNING -> SavingsPalette.AwaitingBg to SavingsPalette.AwaitingFg
        BannerTone.ERROR -> SavingsPalette.ErrorBg to SavingsPalette.ErrorFg
    }
    Text(
        text = text,
        fontSize = 12.sp,
        color = foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

@Composable
internal fun ErrorBanner(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SavingsPalette.ErrorBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = text, fontSize = 12.sp, color = SavingsPalette.ErrorFg)
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text("重试", fontSize = 12.sp, color = SavingsPalette.ErrorFg)
            }
        }
    }
}

@Composable
internal fun LoadingRow(text: String = "正在加载…") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, fontSize = 12.sp, color = SavingsPalette.TextSecondary)
    }
}

@Composable
internal fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = SavingsPalette.TextSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(92.dp),
            fontSize = 12.sp,
            color = SavingsPalette.TextSecondary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            color = SavingsPalette.TextPrimary,
        )
    }
}

@Composable
internal fun FormLabel(text: String) {
    Text(text = text, fontSize = 12.sp, color = SavingsPalette.TextSecondary)
}

/** 统一样式的表单单行输入框。 */
@Composable
internal fun SavingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder.orEmpty()) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = { if (!helper.isNullOrBlank()) Text(helper, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/** 下拉选择（用 OutlinedButton + DropdownMenu 实现，避免依赖实验性 API）。 */
@Composable
internal fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second ?: "请选择"
    Column(modifier = modifier.fillMaxWidth()) {
        FormLabel(label)
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("▾", fontSize = 14.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}

/** 可折叠的次要区块标题。 */
@Composable
internal fun CollapseHeader(text: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾ $text" else "▸ $text",
            fontSize = 13.sp,
            color = SavingsPalette.DeepBlue,
        )
    }
}

// ---------------------------------------------------------------- 文案 / 标签映射

internal fun failureText(t: Throwable): String {
    val api = t as? cn.safebank.app.data.network.ApiException
    if (api != null) {
        return when {
            api.code == cn.safebank.app.data.network.ApiException.NETWORK_ERROR ->
                "无法连接服务器：${api.message}。请确认后端已启动，并在「设置」核对服务器地址（模拟器使用 http://10.0.2.2:8080）。"
            api.isSessionError ->
                "会话已失效，请稍后重试；若持续失败请检查服务器地址。原始信息：${api.message}"
            else -> api.message.ifBlank { "请求未完成，请稍后重试。" }
        }
    }
    val raw = t.message?.trim().orEmpty()
    return if (raw.isBlank()) "请求未完成，请稍后重试。" else raw
}

/** 版本冲突判定：后端 409 CONFLICT / "任务版本已改变"。 */
internal fun isVersionConflict(t: Throwable): Boolean {
    val api = t as? cn.safebank.app.data.network.ApiException
    if (api != null) return api.isConflict || api.code == cn.safebank.app.data.network.ApiException.CONFLICT
    val raw = t.message.orEmpty()
    return raw.contains("版本") || raw.contains("CONFLICT") || raw.contains("冲突")
}

/** 演示验证未启用（默认 profile 下的 503 VERIFICATION_UNAVAILABLE）的明确提示。 */
internal fun verificationFailureText(t: Throwable): String {
    val api = t as? cn.safebank.app.data.network.ApiException
    val unavailable = api != null && api.isVerificationUnavailable
    val raw = failureText(t)
    return if (unavailable || raw.contains("VERIFICATION_UNAVAILABLE") || raw.contains("未启用")) {
        "当前后端未启用演示验证（VERIFICATION_UNAVAILABLE）。红色等级操作需要以 risk-demo profile 启动后端并配置演示验证后才能完成；其他等级操作不受影响。原始信息：$raw"
    } else {
        raw
    }
}

internal fun actionTitle(kind: String?): String = when (kind) {
    "TRANSFER" -> "转账操作"
    "REVOKE" -> "撤销银行侧代扣"
    "SCHEDULE" -> "创建定时转账"
    "QUERY_MANDATES" -> "查询本人银行代扣"
    else -> "银行操作"
}

internal fun nextStepText(status: String?): String = when (status) {
    "AWAITING_AUTH" -> "请先核对以下信息，再勾选确认。聊天中的确认不会执行操作。"
    "AUTHORIZED" -> "授权已记录，尚未提交。请核对最新状态后继续。"
    "CANCELLED" -> "本操作已取消。"
    "VERIFIED_SUCCESS" -> "已核验完成，请查看回执。"
    "VERIFIED_FAILED" -> "本操作未完成，请展开详情查看原因。"
    "PENDING", "UNKNOWN" -> "结果未定，请先查询原操作的最新状态，勿重复创建相同操作。"
    else -> "请先查询原操作的最新状态，勿重复创建相同操作。"
}

internal fun reasonCodeLabel(code: String?): String = when (code) {
    "DAILY_OUTGOING_THRESHOLD_EXCEEDED" -> "超过当日累计转账阈值"
    "EXPLICIT_CONFIRMATION_REQUIRED" -> "需要用户明确确认"
    "PRIOR_STEP_UP_REQUIREMENT" -> "此前操作要求加强验证"
    null, "" -> "—"
    else -> code
}

internal fun errorCodeLabel(code: String?): String? = when (code) {
    null, "" -> null
    "INSUFFICIENT_FUNDS" -> "余额不足"
    "LIMIT_EXCEEDED" -> "超出单笔限额"
    "ALREADY_REVOKED" -> "该代扣已撤销"
    "VALIDATION_ERROR" -> "参数校验失败"
    "AUTH_REQUIRED" -> "缺少有效授权"
    "AUTH_EXPIRED" -> "预览或授权已过期"
    "STEP_UP_REQUIRED" -> "需要加强验证"
    "RISK_DECISION_STALE" -> "风险判定已变化"
    "VERIFICATION_INVALID" -> "验证未通过"
    "VERIFICATION_EXPIRED" -> "验证已过期"
    "VERIFICATION_REPLAYED" -> "验证凭据已被使用"
    "RESPONSE_LOST" -> "执行响应丢失，结果未知"
    "LOOKUP_UNAVAILABLE" -> "查单不可用，结果未知"
    "SIMULATED_BANK_REJECTION" -> "模拟银行明确拒绝"
    "ACTION_ALREADY_COMPLETED" -> "动作已终结"
    "ACTION_RECONCILING" -> "动作核对中"
    else -> code
}

internal fun mandateStatusLabel(status: String?): String = when (status) {
    "ACTIVE" -> "银行侧授权有效"
    "REVOKED" -> "银行侧授权已撤销"
    else -> "银行侧状态未确认"
}

internal fun scheduleStatusLabel(status: String?): String = when (status) {
    "ACTIVE" -> "生效中"
    "CANCELLED" -> "已取消"
    else -> status ?: "未知状态"
}

internal fun planNodeTypeLabel(type: String?): String = when (type) {
    "mandate_lookup" -> "核对代扣查询"
    "account_query" -> "核对账户"
    "mandate_revoke" -> "撤销银行代扣"
    "compute_savings" -> "核算已确认节省"
    "schedule_create" -> "登记定时储蓄"
    else -> "业务步骤"
}

internal fun planNodeDisplayLabel(state: String?): String = when (state) {
    "VALIDATED" -> "前置已校验"
    "WAITING_DEPENDENCY" -> "等待前置步骤"
    "BLOCKED_UNKNOWN" -> "结果未知，等待查单"
    "AWAITING_AUTH" -> "等待逐项确认"
    "AUTHORIZED" -> "已授权，尚未核验完成"
    "VERIFIED_SUCCESS" -> "已核验完成"
    "VERIFIED_FAILED" -> "未完成"
    "CANCELLED" -> "已取消"
    "PENDING" -> "银行处理中"
    else -> state ?: "未知状态"
}

internal fun scheduleSummary(schedule: ScheduleVo): String {
    val amount = schedule.amountMinor?.let { Formatters.money(it) } ?: "—"
    val day = schedule.dayOfMonth?.toString() ?: "—"
    val first = schedule.firstDate ?: "—"
    return "已登记：每月 $day 日 · $amount · 首次 $first"
}

internal fun receiptExplanation(kind: String?, status: String?, hasData: Boolean): String {
    if (status != "VERIFIED_SUCCESS") {
        return "${Formatters.actionStatusLabel(status)}。请以查单结果为准，勿重新创建相同动作。"
    }
    return when (kind) {
        "QUERY_MANDATES" -> if (hasData) {
            "本次代扣查询已完成；查询没有转账或记账。"
        } else {
            "这是原查询回执，重新查询需再次确认范围。"
        }
        "SCHEDULE" -> "定时计划已登记，尚未发生转账。"
        "REVOKE" -> "银行侧代扣已撤销，商户合同状态未知，余额没有因此增加。"
        "TRANSFER" -> "转账已完成，并核验双边账务分录。"
        else -> "已核验完成，请以查单结果为准。"
    }
}

internal fun workflowStateHint(workflow: cn.safebank.app.data.model.WorkflowVo): String? = when {
    workflow.partial -> "已有步骤完成；取消或修改后续任务不会撤回这些银行操作。"
    workflow.state == "VERIFIED_SUCCESS" || workflow.state == "PARTIAL_SUCCESS" ->
        "请逐项检查结果。定时计划登记不代表未来转账已发生；商户合同状态仍未知。"
    else -> null
}

// ---------------------------------------------------------------- 表单校验

internal fun parseDate(raw: String): LocalDate? = try {
    LocalDate.parse(raw.trim())
} catch (e: Exception) {
    null
}

internal fun validateScheduleForm(amountText: String, firstDate: String, endDate: String): String? {
    val amount = Formatters.parseAmountToMinor(amountText)
    if (amount == null || amount <= 0) return "每月金额必须为正数，最多两位小数（元）。"
    if (amount > 1_000_000) return "演示单笔限额 ¥10,000，请调低每月金额。"
    val first = parseDate(firstDate) ?: return "首次日期格式应为 YYYY-MM-DD。"
    val end = parseDate(endDate) ?: return "结束日期格式应为 YYYY-MM-DD。"
    if (first.dayOfMonth !in 1..31) return "首次日期的日必须在 1—31 日之间；短月按该月最后一天执行。"
    if (end.isBefore(first)) return "结束日期不能早于首次日期。"
    if (end.isAfter(first.plusYears(1))) return "结束日期最长为首次日期后一年。"
    return null
}

internal fun validateWorkflowForm(
    month: String,
    firstDate: String,
    endDate: String,
    sourceId: String,
    recipientId: String,
    mandateIds: List<String>,
): String? {
    if (!Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(month.trim())) {
        return "账单月份格式应为 YYYY-MM（例如 2026-09）。"
    }
    if (mandateIds.isEmpty()) return "请明确选定至少一项月度代扣。"
    if (mandateIds.size > 10) return "一次最多选择 10 项代扣。"
    if (mandateIds.distinct().size != mandateIds.size) return "代扣选择有重复项，请重新勾选。"
    if (sourceId.isBlank()) return "请选择付款账户。"
    if (recipientId.isBlank()) return "请选择储蓄目标。"
    if (sourceId == recipientId) return "储蓄目标不能与付款账户相同。"
    val first = parseDate(firstDate) ?: return "首次日期格式应为 YYYY-MM-DD。"
    val end = parseDate(endDate) ?: return "结束日期格式应为 YYYY-MM-DD。"
    if (first.dayOfMonth !in 1..31) return "首次日期的日必须在 1—31 日之间；短月按该月最后一天执行。"
    if (end.isBefore(first)) return "结束日期不能早于首次日期。"
    if (end.isAfter(first.plusYears(1))) return "结束日期最长为首次日期后一年。"
    val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    if (first.isBefore(today)) return "首次日期必须是今天或未来日期。"
    return null
}
