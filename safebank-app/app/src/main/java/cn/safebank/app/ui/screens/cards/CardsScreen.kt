package cn.safebank.app.ui.screens.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.BankCardVo
import cn.safebank.app.data.model.CardChangeRequest
import cn.safebank.app.data.model.CardOperationVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BadgeTone
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.Divider
import cn.safebank.app.ui.components.EmptyState
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.KeyValueRow
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.PrimaryButton
import cn.safebank.app.ui.components.SecondaryButton
import cn.safebank.app.ui.components.SectionCard
import cn.safebank.app.ui.components.StatusBadge
import cn.safebank.app.ui.components.TextActionButton
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar

/**
 * 卡片管理（t020 后端 `/api/cards`）。
 *
 * 支持的操作（全部走「预览 → 确认 / 取消」两步流程）：
 * APPLY / UPDATE_LIMITS / UPDATE_CONTROLS / LOCK / UNLOCK / REPORT_LOST。
 * 状态常量只有 `AWAITING_CONFIRMATION` / `COMPLETED` / `CANCELLED`。
 *
 * 页面布局：顶部标题栏（保留返回箭头）+ 可滚动内容（成功/错误横幅、待确认变更、
 * 我的银行卡、申请新卡、调整限额、挂失、最近操作）。
 */
@Composable
fun CardsRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val state = remember { CardsStateHolder(scope, container.repository) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        container.repository.ensureSession(false)
        state.reload()
        state.loadAccounts()
    }

    // 生成预览 / 出现操作失败时回到顶部：确保「待确认变更」与错误提示一定可见。
    LaunchedEffect(state.pending?.id) {
        if (state.pending != null) scrollState.animateScrollTo(0)
    }
    LaunchedEffect(state.actionError) {
        if (state.actionError != null) scrollState.animateScrollTo(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(navigator, "卡片管理")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.notice?.let { message ->
                InfoBanner(
                    text = message,
                    tone = BannerTone.Success,
                    action = { TextActionButton(text = "知道了", onClick = { state.clearNotice() }) },
                )
            }

            state.actionError?.let { message ->
                InfoBanner(
                    text = message,
                    tone = BannerTone.Error,
                    action = { TextActionButton(text = "关闭", onClick = { state.clearActionError() }) },
                )
            }

            state.pending?.let { operation ->
                PendingPreviewCard(operation = operation, state = state)
            }

            CardsSection(state = state)
            ApplyCardSection(state = state)
            UpdateLimitsSection(state = state)
            ReportLostSection(state = state)
            OperationsSection(state = state)

            Text(
                text = "说明：预览本身不会改变卡片，确认后才生效；预览 10 分钟内有效。" +
                    "挂失不可逆，挂失后只能重新申请新卡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 我的银行卡

@Composable
private fun CardsSection(state: CardsStateHolder) {
    SectionCard(
        title = "我的银行卡",
        subtitle = "余额、限额与支付开关；锁定 / 解锁 / 开关都走预览→确认。",
        action = {
            TextActionButton(
                text = "刷新",
                onClick = { state.reload() },
                enabled = !state.loadingCards && !state.loadingOps,
            )
        },
    ) {
        when {
            state.loadingCards && state.cards.isEmpty() -> LoadingRow("正在读取卡片…")
            state.cardsError != null && state.cards.isEmpty() -> InfoBanner(
                text = "卡片读取失败：${state.cardsError}",
                tone = BannerTone.Error,
                action = { TextActionButton(text = "重试", onClick = { state.loadCards() }) },
            )
            state.cards.isEmpty() -> EmptyState(
                title = "暂无卡片",
                hint = "可在下方「申请新卡」中生成一张演示卡（预览→确认后生效）。",
            )
            else -> {
                if (state.cardsError != null) {
                    InfoBanner(text = "卡片刷新失败：${state.cardsError}", tone = BannerTone.Error)
                    Spacer(Modifier.height(8.dp))
                }
                state.cards.forEachIndexed { index, card ->
                    BankCardRow(card = card, state = state)
                    if (index != state.cards.lastIndex) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BankCardRow(card: BankCardVo, state: CardsStateHolder) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.maskedNumber.ifBlank { card.label },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${card.label} · ${Formatters.cardTypeLabel(card.cardType)} · ${card.accountLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(text = Formatters.cardStatusLabel(card.status), tone = cardStatusTone(card.status))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "余额 ${Formatters.money(card.balanceMinor)} · " +
                "单笔 ${Formatters.money(card.perTransactionLimitMinor)} · " +
                "日限 ${Formatters.money(card.dailyLimitMinor)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "在线支付${if (card.onlineEnabled) "已开启" else "已关闭"} · " +
                "境外支付${if (card.overseasEnabled) "已开启" else "已关闭"} · " +
                "版本 v${card.version}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.status == "LOST") {
            Text(
                text = "该卡已挂失，不可解锁或恢复；如需用卡请重新申请。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (card.status == "ACTIVE") {
                    SecondaryButton(
                        text = "锁定",
                        onClick = {
                            state.preview(
                                CardChangeRequest(kind = "LOCK", cardId = card.id),
                                successNotice = "已生成锁定预览，请在「待确认变更」中确认。",
                            )
                        },
                        enabled = !state.actionBusy,
                        modifier = Modifier.weight(1f),
                    )
                } else if (card.status == "LOCKED") {
                    SecondaryButton(
                        text = "解锁",
                        onClick = {
                            state.preview(
                                CardChangeRequest(kind = "UNLOCK", cardId = card.id),
                                successNotice = "已生成解锁预览，请在「待确认变更」中确认。",
                            )
                        },
                        enabled = !state.actionBusy,
                        modifier = Modifier.weight(1f),
                    )
                }
                SecondaryButton(
                    text = if (card.onlineEnabled) "关闭在线" else "开启在线",
                    onClick = {
                        // 后端要求两个开关必须同时给值：被点的一个取反，另一个保持卡片当前值。
                        state.preview(
                            CardChangeRequest(
                                kind = "UPDATE_CONTROLS",
                                cardId = card.id,
                                onlineEnabled = !card.onlineEnabled,
                                overseasEnabled = card.overseasEnabled,
                            ),
                            successNotice = "已生成在线支付开关变更预览，请在「待确认变更」中确认。",
                        )
                    },
                    enabled = !state.actionBusy,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = if (card.overseasEnabled) "关闭境外" else "开启境外",
                    onClick = {
                        state.preview(
                            CardChangeRequest(
                                kind = "UPDATE_CONTROLS",
                                cardId = card.id,
                                onlineEnabled = card.onlineEnabled,
                                overseasEnabled = !card.overseasEnabled,
                            ),
                            successNotice = "已生成境外支付开关变更预览，请在「待确认变更」中确认。",
                        )
                    },
                    enabled = !state.actionBusy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 待确认变更

@Composable
private fun PendingPreviewCard(operation: CardOperationVo, state: CardsStateHolder) {
    val request = operation.request
    SectionCard(
        title = "待确认变更",
        subtitle = "预览不改变卡片；确认后才会生效，预览 10 分钟内有效。",
        action = {
            StatusBadge(
                text = Formatters.cardOperationStatusLabel(operation.status),
                tone = cardOperationTone(operation.status),
            )
        },
    ) {
        KeyValueRow("操作类型", Formatters.cardOperationKindLabel(operation.kind))
        KeyValueRow("目标", pendingTargetText(operation, state))
        when (operation.kind) {
            "APPLY" -> {
                request?.label?.takeIf { it.isNotBlank() }?.let { KeyValueRow("卡片名称", it) }
                request?.cardType?.let { KeyValueRow("卡片类型", Formatters.cardTypeLabel(it)) }
                request?.perTransactionLimitMinor?.let { KeyValueRow("单笔限额", Formatters.money(it)) }
                request?.dailyLimitMinor?.let { KeyValueRow("日限额", Formatters.money(it)) }
                request?.onlineEnabled?.let { KeyValueRow("在线支付", if (it) "开启" else "关闭") }
                request?.overseasEnabled?.let { KeyValueRow("境外支付", if (it) "开启" else "关闭") }
            }
            "UPDATE_LIMITS" -> {
                val targetCard = request?.cardId?.let { id -> state.cards.firstOrNull { it.id == id } }
                targetCard?.let { KeyValueRow("当前单笔", Formatters.money(it.perTransactionLimitMinor)) }
                targetCard?.let { KeyValueRow("当前日限", Formatters.money(it.dailyLimitMinor)) }
                request?.perTransactionLimitMinor?.let { KeyValueRow("新单笔限额", Formatters.money(it)) }
                request?.dailyLimitMinor?.let { KeyValueRow("新日限额", Formatters.money(it)) }
            }
            "UPDATE_CONTROLS" -> {
                request?.onlineEnabled?.let { KeyValueRow("在线支付", if (it) "开启" else "关闭") }
                request?.overseasEnabled?.let { KeyValueRow("境外支付", if (it) "开启" else "关闭") }
            }
            "REPORT_LOST" -> {
                Text(
                    text = "挂失不可逆：确认后卡片状态变为「已挂失」，不能解锁或恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            else -> Unit
        }
        KeyValueRow("预览编号", operation.id)
        KeyValueRow("参数指纹", operation.payloadHash.take(16) + "…")
        KeyValueRow("生成时间", Formatters.shortDateTime(operation.createdAt))
        operation.expiresAt?.let { KeyValueRow("过期时间", Formatters.shortDateTime(it)) }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = "确认变更",
                onClick = { state.confirm(operation) },
                enabled = !state.actionBusy,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "取消本次变更",
                onClick = { state.cancel(operation) },
                enabled = !state.actionBusy,
                modifier = Modifier.weight(1f),
            )
        }
        TextActionButton(
            text = "稍后处理（仍保留在操作历史中）",
            onClick = { state.dismissPending() },
            enabled = !state.actionBusy,
        )
    }
}

private fun pendingTargetText(operation: CardOperationVo, state: CardsStateHolder): String {
    val request = operation.request
    val targetCard = request?.cardId?.let { id -> state.cards.firstOrNull { it.id == id } }
    if (targetCard != null) return "${targetCard.maskedNumber} · ${targetCard.label}"
    if (operation.kind == "APPLY") {
        val accountLabel = state.accounts.firstOrNull { it.id == request?.accountId }?.label
        return listOfNotNull(accountLabel ?: request?.accountId, request?.label)
            .joinToString(" · ")
            .ifBlank { "—" }
    }
    return request?.cardId ?: request?.accountId ?: "—"
}

// ---------------------------------------------------------------- 最近操作

@Composable
private fun OperationsSection(state: CardsStateHolder) {
    val history = state.operations
        .filter { it.id != state.pending?.id }
        .take(10)
    SectionCard(
        title = "最近操作",
        subtitle = "历史变更与待确认项；待确认项可直接确认或取消。",
        action = {
            TextActionButton(
                text = "刷新",
                onClick = { state.loadOperations() },
                enabled = !state.loadingOps,
            )
        },
    ) {
        when {
            state.loadingOps && state.operations.isEmpty() -> LoadingRow("正在读取操作历史…")
            state.opsError != null && state.operations.isEmpty() -> InfoBanner(
                text = "操作历史读取失败：${state.opsError}",
                tone = BannerTone.Error,
                action = { TextActionButton(text = "重试", onClick = { state.loadOperations() }) },
            )
            history.isEmpty() -> EmptyState(
                title = "暂无历史",
                hint = "发起一次卡片变更后会在这里显示操作状态与回执编号。",
            )
            else -> {
                if (state.opsError != null) {
                    InfoBanner(text = "操作历史刷新失败：${state.opsError}", tone = BannerTone.Error)
                    Spacer(Modifier.height(8.dp))
                }
                history.forEachIndexed { index, operation ->
                    OperationRow(operation = operation, state = state)
                    if (index != history.lastIndex) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(operation: CardOperationVo, state: CardsStateHolder) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Formatters.cardOperationKindLabel(operation.kind),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = operationTargetText(operation, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(
                text = Formatters.cardOperationStatusLabel(operation.status),
                tone = cardOperationTone(operation.status),
            )
        }
        val detail = operationDetailText(operation)
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = buildString {
                append("创建 ${Formatters.shortDateTime(operation.createdAt)}")
                operation.executedAt?.let { append(" · 执行 ${Formatters.shortDateTime(it)}") }
                operation.receiptId?.let { append(" · 回执 $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (operation.status == "AWAITING_CONFIRMATION") {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    text = "确认变更",
                    onClick = { state.confirm(operation) },
                    enabled = !state.actionBusy,
                    modifier = Modifier.weight(1f),
                )
                TextActionButton(
                    text = "取消",
                    onClick = { state.cancel(operation) },
                    enabled = !state.actionBusy,
                )
            }
            Text(
                text = "确认前不会改变卡片；预览过期后需要重新生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun operationTargetText(operation: CardOperationVo, state: CardsStateHolder): String {
    val request = operation.request
    val card = operation.card ?: request?.cardId?.let { id -> state.cards.firstOrNull { it.id == id } }
    if (card != null) return "${card.maskedNumber} · ${card.label}"
    if (operation.kind == "APPLY") {
        val accountLabel = state.accounts.firstOrNull { it.id == request?.accountId }?.label
        return listOfNotNull(accountLabel ?: request?.accountId, request?.label)
            .joinToString(" · ")
            .ifBlank { "—" }
    }
    return request?.cardId ?: request?.accountId ?: "—"
}

private fun operationDetailText(operation: CardOperationVo): String {
    val request = operation.request ?: return ""
    return when (operation.kind) {
        "APPLY" -> listOfNotNull(
            request.label?.takeIf { it.isNotBlank() }?.let { "卡名 $it" },
            request.cardType?.let { Formatters.cardTypeLabel(it) },
            request.perTransactionLimitMinor?.let { "单笔 ${Formatters.money(it)}" },
            request.dailyLimitMinor?.let { "日限 ${Formatters.money(it)}" },
        ).joinToString(" · ")
        "UPDATE_LIMITS" -> listOfNotNull(
            request.perTransactionLimitMinor?.let { "单笔 ${Formatters.money(it)}" },
            request.dailyLimitMinor?.let { "日限 ${Formatters.money(it)}" },
        ).joinToString(" · ")
        "UPDATE_CONTROLS" -> listOfNotNull(
            request.onlineEnabled?.let { "在线${if (it) "开启" else "关闭"}" },
            request.overseasEnabled?.let { "境外${if (it) "开启" else "关闭"}" },
        ).joinToString(" · ")
        else -> ""
    }
}

// ---------------------------------------------------------------- 标签映射

private fun cardStatusTone(status: String?): BadgeTone = when (status) {
    "ACTIVE" -> BadgeTone.Success
    "LOCKED" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}

private fun cardOperationTone(status: String?): BadgeTone = when (status) {
    "AWAITING_CONFIRMATION" -> BadgeTone.Warning
    "COMPLETED" -> BadgeTone.Success
    "CANCELLED" -> BadgeTone.Neutral
    else -> BadgeTone.Default
}
