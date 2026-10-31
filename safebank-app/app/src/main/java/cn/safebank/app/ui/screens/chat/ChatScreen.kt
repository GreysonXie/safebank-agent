package cn.safebank.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.ui.navigation.AppDestination
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.screens.actions.ActionCard

/**
 * 「对话助手」tab（ui-design-spec §4.1；契约 §4.29–§4.32）。
 *
 * 结构（自上而下）：标题 + 模式徽章 + 模型信息 → 上下文记忆区 → 消息列表（含本轮查询依据、
 * 内联待确认操作卡）→ 状态行 → 错误横幅 → 建议 chips → 输入区。
 *
 * 状态保存在 [ChatStateHolder]（进程级缓存，切 tab 不丢草稿/待确认卡）。
 */
@Composable
fun ChatRoute(navigator: AppNavigator, container: AppContainer) {
    val holder = remember { ChatScreens.of(container.repository) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // 新消息 / 新卡片后自动滚到底部
    LaunchedEffect(holder.messages.size, holder.actionCards.size) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            runCatching { listState.animateScrollToItem(total - 1) }
        }
    }

    // 点候选收款人后回填并聚焦输入框（不自动发送）
    LaunchedEffect(holder.focusTick) {
        if (holder.focusTick > 0) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ChatHeader(holder = holder, onOpenActions = { navigator.push(AppDestination.Actions) })
        ContextBar(holder)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = holder.messages,
                key = { index, _ -> "msg-$index" },
            ) { _, message ->
                MessageBubble(message)
            }

            if (holder.evidence != null) {
                item(key = "evidence") { EvidenceSection(holder) }
            }

            if (holder.actionCards.isNotEmpty()) {
                item(key = "actions-header") { PendingActionsHeader(holder.actionCards.size) }
                items(holder.actionCards, key = { card -> "action-${card.id}" }) { card ->
                    ActionCard(card = card)
                }
            }
        }

        ChatStatusLine(holder)
        ChatBannerArea(holder)
        ClarificationCard(holder)
        SuggestionRow(
            enabled = !holder.busy,
            onSend = { actualText -> holder.sendSuggestion(actualText) },
        )
        ChatInputArea(holder = holder, focusRequester = focusRequester)
        Spacer(Modifier.height(6.dp))
    }
}
