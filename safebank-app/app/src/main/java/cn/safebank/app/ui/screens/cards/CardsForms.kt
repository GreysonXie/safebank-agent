package cn.safebank.app.ui.screens.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.safebank.app.data.model.BankCardVo
import cn.safebank.app.data.model.CardChangeRequest
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.FormField
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.LabeledField
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.PrimaryButton
import cn.safebank.app.ui.components.SecondaryButton
import cn.safebank.app.ui.components.SectionCard
import cn.safebank.app.ui.components.SelectableRow
import java.math.BigDecimal

/**
 * 卡片管理的表单区：申请新卡（APPLY）、调整限额（UPDATE_LIMITS）、挂失（REPORT_LOST）。
 *
 * 三个入口都走同一条两步流程：本页只负责「校验 + 生成预览」，
 * 预览后的「确认 / 取消」统一由 [CardsStateHolder.confirm] / [CardsStateHolder.cancel] 处理。
 *
 * 限额输入统一用「元」，提交前用 [Formatters.parseAmountToMinor] 转整数分（最多两位小数）。
 */
@Composable
internal fun ApplyCardSection(state: CardsStateHolder) {
    var accountId by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var cardType by remember { mutableStateOf("DEBIT") }
    var perTransactionYuan by remember { mutableStateOf("5000") }
    var dailyYuan by remember { mutableStateOf("20000") }
    var onlineEnabled by remember { mutableStateOf(true) }
    var overseasEnabled by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.accounts) {
        if (accountId.isBlank() || state.accounts.none { it.id == accountId }) {
            accountId = state.accounts.firstOrNull()?.id.orEmpty()
        }
    }

    SectionCard(
        title = "申请新卡",
        subtitle = "先预览再确认；同一账户最多 8 张未挂失卡，确认后立即生效。",
    ) {
        when {
            state.loadingAccounts && state.accounts.isEmpty() -> LoadingRow("正在读取账户…")
            state.accountsError != null && state.accounts.isEmpty() -> {
                InfoBanner(
                    text = "账户读取失败：${state.accountsError}",
                    tone = BannerTone.Error,
                    action = { TextButton(onClick = { state.loadAccounts() }) { Text("重试") } },
                )
            }
            state.accounts.isEmpty() -> InfoBanner(
                text = "暂无可用账户，无法申请新卡。请先确认后端会话与账户数据。",
                tone = BannerTone.Warning,
            )
            else -> {
                CardSelector(
                    label = "付款账户",
                    options = state.accounts.map { account ->
                        account.id to "${account.label} · ${Formatters.money(account.balanceMinor)}"
                    },
                    selectedId = accountId,
                    onSelect = {
                        accountId = it
                        formError = null
                    },
                )
                Spacer(Modifier.height(12.dp))
                LabeledField(label = "卡片类型") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectableRow(
                            selected = cardType == "DEBIT",
                            label = Formatters.cardTypeLabel("DEBIT"),
                            onClick = {
                                cardType = "DEBIT"
                                formError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SelectableRow(
                            selected = cardType == "VIRTUAL",
                            label = Formatters.cardTypeLabel("VIRTUAL"),
                            onClick = {
                                cardType = "VIRTUAL"
                                formError = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                FormField(
                    label = "卡片名称（40 字以内）",
                    value = label,
                    onValueChange = {
                        label = it
                        formError = null
                    },
                    placeholder = "例如：日常消费卡",
                )
                Spacer(Modifier.height(12.dp))
                FormField(
                    label = "单笔限额（元）",
                    value = perTransactionYuan,
                    onValueChange = {
                        perTransactionYuan = it
                        formError = null
                    },
                    placeholder = "例如：5000",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(12.dp))
                FormField(
                    label = "日限额（元，最高 ¥100,000.00）",
                    value = dailyYuan,
                    onValueChange = {
                        dailyYuan = it
                        formError = null
                    },
                    placeholder = "例如：20000",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "在线支付",
                    checked = onlineEnabled,
                    onCheckedChange = { onlineEnabled = it },
                )
                ToggleRow(
                    label = "境外支付",
                    checked = overseasEnabled,
                    onCheckedChange = { overseasEnabled = it },
                )
                Spacer(Modifier.height(8.dp))
                HintText("两个开关都会显式提交给后端；单笔限额须大于 0 且不超过日限额。")
                formError?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(text = message, tone = BannerTone.Error)
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = "生成申请预览",
                    onClick = {
                        val account = state.accounts.firstOrNull { it.id == accountId }
                        val trimmedLabel = label.trim()
                        val (limits, limitError) = parseLimits(perTransactionYuan, dailyYuan)
                        val validation = when {
                            account == null -> "请选择付款账户。"
                            trimmedLabel.isEmpty() -> "请填写卡片名称。"
                            trimmedLabel.length > 40 -> "卡片名称不能超过 40 个字。"
                            state.activeCards.size >= 8 -> "最多持有 8 张未挂失卡片；请先挂失不用的卡片再申请。"
                            limitError != null -> limitError
                            else -> null
                        }
                        if (validation != null) {
                            formError = validation
                        } else if (account != null && limits != null) {
                            formError = null
                            state.preview(
                                CardChangeRequest(
                                    kind = "APPLY",
                                    accountId = account.id,
                                    label = trimmedLabel,
                                    cardType = cardType,
                                    perTransactionLimitMinor = limits.perTransactionMinor,
                                    dailyLimitMinor = limits.dailyMinor,
                                    onlineEnabled = onlineEnabled,
                                    overseasEnabled = overseasEnabled,
                                ),
                                successNotice = "已生成申请预览，请在「待确认变更」中核对参数并确认。",
                            )
                        }
                    },
                    enabled = !state.actionBusy,
                )
                HintText("当前未挂失卡片：${state.activeCards.size} / 8 张。")
            }
        }
    }
}

@Composable
internal fun UpdateLimitsSection(state: CardsStateHolder) {
    val eligible = state.cards.filter { it.status != "LOST" }
    var cardId by remember { mutableStateOf("") }
    var perTransactionYuan by remember { mutableStateOf("") }
    var dailyYuan by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(eligible) {
        if (cardId.isBlank() || eligible.none { it.id == cardId }) {
            cardId = eligible.firstOrNull()?.id.orEmpty()
        }
    }
    val selected = eligible.firstOrNull { it.id == cardId }
    LaunchedEffect(cardId, selected?.version) {
        if (selected != null) {
            perTransactionYuan = minorToYuanInput(selected.perTransactionLimitMinor)
            dailyYuan = minorToYuanInput(selected.dailyLimitMinor)
        }
    }

    SectionCard(
        title = "调整限额",
        subtitle = "单笔限额须大于 0 且不超过日限额，日限额最高 ¥100,000.00。",
    ) {
        if (eligible.isEmpty()) {
            InfoBanner(
                text = "暂无可调整限额的卡片（已挂失卡片不可变更）。",
                tone = BannerTone.Warning,
            )
        } else {
            CardSelector(
                label = "选择卡片",
                options = eligible.map { it.id to cardSummary(it) },
                selectedId = cardId,
                onSelect = {
                    cardId = it
                    formError = null
                },
            )
            Spacer(Modifier.height(12.dp))
            FormField(
                label = "单笔限额（元）",
                value = perTransactionYuan,
                onValueChange = {
                    perTransactionYuan = it
                    formError = null
                },
                placeholder = "例如：5000",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(12.dp))
            FormField(
                label = "日限额（元）",
                value = dailyYuan,
                onValueChange = {
                    dailyYuan = it
                    formError = null
                },
                placeholder = "例如：20000",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            val (limits, _) = parseLimits(perTransactionYuan, dailyYuan)
            if (selected != null && limits != null) {
                Spacer(Modifier.height(6.dp))
                HintText(
                    "当前：单笔 ${Formatters.money(selected.perTransactionLimitMinor)} · " +
                        "日限 ${Formatters.money(selected.dailyLimitMinor)}；" +
                        "变更后：单笔 ${Formatters.money(limits.perTransactionMinor)} · " +
                        "日限 ${Formatters.money(limits.dailyMinor)}",
                )
            }
            formError?.let { message ->
                Spacer(Modifier.height(8.dp))
                InfoBanner(text = message, tone = BannerTone.Error)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "生成限额变更预览",
                onClick = {
                    val (newLimits, limitError) = parseLimits(perTransactionYuan, dailyYuan)
                    if (selected == null) {
                        formError = "请选择要调整限额的卡片。"
                    } else if (limitError != null) {
                        formError = limitError
                    } else if (newLimits != null) {
                        formError = null
                        state.preview(
                            CardChangeRequest(
                                kind = "UPDATE_LIMITS",
                                cardId = selected.id,
                                perTransactionLimitMinor = newLimits.perTransactionMinor,
                                dailyLimitMinor = newLimits.dailyMinor,
                            ),
                            successNotice = "已生成限额变更预览，请在「待确认变更」中核对后确认。",
                        )
                    }
                },
                enabled = !state.actionBusy,
            )
        }
    }
}

@Composable
internal fun ReportLostSection(state: CardsStateHolder) {
    val active = state.cards.filter { it.status == "ACTIVE" }
    var cardId by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<BankCardVo?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active) {
        if (cardId.isBlank() || active.none { it.id == cardId }) {
            cardId = active.firstOrNull()?.id.orEmpty()
        }
    }

    SectionCard(
        title = "挂失卡片",
        subtitle = "挂失不可逆：挂失后卡片不能解锁或恢复，只能重新申请新卡。",
    ) {
        if (active.isEmpty()) {
            InfoBanner(
                text = "当前没有可挂失的卡片（仅「正常」状态的卡片可挂失）。",
                tone = BannerTone.Warning,
            )
        } else {
            CardSelector(
                label = "选择要挂失的卡片",
                options = active.map { it.id to cardSummary(it) },
                selectedId = cardId,
                onSelect = {
                    cardId = it
                    formError = null
                },
            )
            formError?.let { message ->
                Spacer(Modifier.height(8.dp))
                InfoBanner(text = message, tone = BannerTone.Error)
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                text = "挂失该卡…",
                onClick = {
                    val target = active.firstOrNull { it.id == cardId }
                    if (target == null) {
                        formError = "请选择要挂失的卡片。"
                    } else {
                        formError = null
                        confirming = target
                    }
                },
                enabled = !state.actionBusy,
            )
            Spacer(Modifier.height(6.dp))
            HintText("挂失前会先弹出二次确认，再生成预览；预览仍需在「待确认变更」中确认后才生效。")
        }
    }

    val target = confirming
    if (target != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("确认挂失 ${target.maskedNumber}？") },
            text = {
                Column {
                    Text("挂失不可逆：卡片状态会变为「已挂失」，不能解锁、不能恢复，也不能再用于任何交易。")
                    Spacer(Modifier.height(8.dp))
                    Text("本步骤只生成挂失预览，仍需在「待确认变更」中再次确认后才会真正挂失。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val card = target
                    confirming = null
                    state.preview(
                        CardChangeRequest(kind = "REPORT_LOST", cardId = card.id),
                        successNotice = "已生成挂失预览，请在「待确认变更」中再次确认。",
                    )
                }) { Text("生成挂失预览") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 表单/私有组件

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 下拉选择（OutlinedButton + DropdownMenu，和储蓄页表单控件观感一致，不依赖实验性 API）。 */
@Composable
private fun CardSelector(
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
        LabeledField(label = label) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled && options.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = selectedLabel,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("▾", style = MaterialTheme.typography.bodyMedium)
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
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun cardSummary(card: BankCardVo): String {
    val number = card.maskedNumber.ifBlank { card.id }
    return "$number · ${Formatters.cardTypeLabel(card.cardType)} · ${Formatters.cardStatusLabel(card.status)}"
}

private data class LimitInputs(
    val perTransactionMinor: Long,
    val dailyMinor: Long,
)

/**
 * 校验并解析两个限额（元 → 分）。
 *
 * 后端约束：`1 <= 单笔 <= 日限额`，且 `日限额 <= 10,000,000 分（¥100,000）`。
 *
 * @return Pair(解析结果, 中文错误文案)；两者互斥，合法时错误为 null。
 */
private fun parseLimits(perTransactionYuan: String, dailyYuan: String): Pair<LimitInputs?, String?> {
    val perTransaction = Formatters.parseAmountToMinor(perTransactionYuan)
    val daily = Formatters.parseAmountToMinor(dailyYuan)
    if (perTransaction == null || perTransaction < 1L) {
        return null to "单笔限额需为大于 0 的金额（元，最多两位小数）。"
    }
    if (daily == null || daily < 1L) {
        return null to "日限额需为大于 0 的金额（元，最多两位小数）。"
    }
    if (perTransaction > daily) {
        return null to "单笔限额不能超过日限额。"
    }
    if (daily > 10_000_000L) {
        return null to "日限额最高 ¥100,000.00。"
    }
    return LimitInputs(perTransaction, daily) to null
}

/** 整数分 → 可编辑的「元」字符串（去掉多余的 0 和小数点）。 */
private fun minorToYuanInput(minor: Long): String =
    BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
