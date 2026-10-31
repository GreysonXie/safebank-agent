package cn.safebank.app.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.data.util.Formatters
import com.google.gson.JsonElement

/**
 * 对话页私有组件（ui-design-spec §3.10 气泡 / §3.11 chips / §3.12 上下文 / §3.7 查询依据）。
 *
 * 说明：公共组件由 ui-shell 负责人在 ui/components 目录并行实现；为避免跨作用域耦合，
 * 本页只使用 Material3 原生组件 + 本包私有组件。
 */

@Composable
internal fun ChatHeader(
    holder: ChatStateHolder,
    onOpenActions: () -> Unit,
) {
    val failed = holder.sessionError != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "对话助手",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "从你的问题开始，结合业务数据继续追问。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { holder.refreshSession() },
                enabled = !holder.bootstrapping && !holder.sending,
            ) {
                Text("刷新", fontSize = 12.sp)
            }
            TextButton(onClick = onOpenActions) {
                Text("待确认操作", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeBadge(
                text = modeBadge(
                    mode = holder.session?.mode,
                    bootstrapping = holder.bootstrapping,
                    sessionFailed = failed,
                ),
                alert = failed,
            )
            if (holder.session?.user?.isNotBlank() == true) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "当前账号：${holder.session?.user}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = modeInfo(
                mode = holder.session?.mode,
                model = holder.session?.model,
                sessionFailed = failed,
                errorText = holder.sessionError,
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ModeBadge(text: String, alert: Boolean) {
    val background = if (alert) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (alert) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = background, shape = RoundedCornerShape(5.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            fontSize = 11.sp,
            color = foreground,
        )
    }
}

/** 上下文记忆区（§3.12）：状态 + 意图 + 槽位摘要 + 清空草案。 */
@Composable
internal fun ContextBar(holder: ChatStateHolder) {
    val context = holder.conversation
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contextStateLabel(context?.state),
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "意图：${intentLabel(context?.intent)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = slotSummary(context?.slots.orEmpty()),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (holder.hasConversation || context != null) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { holder.resetDraft() },
                        enabled = !holder.busy && holder.hasConversation,
                    ) {
                        Text(if (holder.resetting) "清空中…" else "清空对话草案", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 气泡（§3.10）：助手左 / 用户右，署名小字，文本按 \n 分段。 */
@Composable
internal fun MessageBubble(message: ChatMessageUi) {
    val isUser = message.role == ChatRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = if (isUser) "你的需求" else "SafeBank",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
        ) {
            val paragraphs = message.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (paragraphs.isEmpty()) {
                    Text(text = "（无内容）", fontSize = 14.sp)
                } else {
                    paragraphs.forEachIndexed { index, paragraph ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        Text(
                            text = paragraph,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 本轮查询依据（§3.4 / B-25）：trace / results / answerSource / modelUsage / timing。 */
@Composable
internal fun EvidenceSection(holder: ChatStateHolder) {
    val evidence = holder.evidence ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { holder.toggleEvidence() }) {
            Text(
                text = if (holder.evidenceExpanded) "收起本轮查询依据" else "查看本轮查询依据",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "（${evidence.trace.size} 条轨迹）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (holder.evidenceExpanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                answerSourceLabel(evidence.answerSource)?.let { label ->
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                evidence.statement?.let { statement ->
                    StatementSummary(statement)
                    Spacer(Modifier.height(8.dp))
                }

                if (evidence.trace.isNotEmpty()) {
                    Text(text = "工具轨迹", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    evidence.trace.forEach { entry ->
                        Text(
                            text = "· ${traceLabel(entry)}",
                            modifier = Modifier.padding(top = 3.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (evidence.results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "工具结果", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    evidence.results.forEachIndexed { index, element ->
                        Text(
                            text = "${index + 1}. ${toolLabel(element.resultTool())}",
                            modifier = Modifier.padding(top = 3.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { holder.usageExpanded = !holder.usageExpanded }) {
                    Text(
                        text = if (holder.usageExpanded) "收起模型调用统计" else "模型调用统计",
                        fontSize = 11.sp,
                    )
                }
                if (holder.usageExpanded) {
                    val usage = evidence.modelUsage
                    val timing = evidence.timing
                    val requests = usage.jsonInt("request_count") ?: 0
                    val limit = usage.jsonInt("request_limit") ?: 0
                    val generation = usage.jsonInt("generation_requests") ?: 0
                    val review = usage.jsonInt("review_requests") ?: 0
                    val tokens = usage.jsonLong("total_tokens")
                    Text(
                        text = "模型请求 $requests/$limit · 生成 $generation · 复核 $review · " +
                            "tokens ${tokens ?: "未提供"}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val elapsed = timing.jsonLong("requestElapsedMs")
                    if (elapsed != null) {
                        Text(
                            text = "服务端耗时 $elapsed ms" +
                                if (timing.jsonBoolean("authorizationWaitIncluded") == true) "（含授权等待）" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (evidence.factReferences.isNotEmpty()) {
                        Text(
                            text = "引用事实：${evidence.factReferences.joinToString("、")}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun StatementSummary(statement: StatementVo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "${statement.month} 账单",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Metric("收入", Formatters.money(statement.incomeMinor), Modifier.weight(1f))
                Metric("支出", Formatters.money(statement.expenseMinor), Modifier.weight(1f))
                Metric("内部转账", Formatters.money(statement.internalTransferMinor), Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "净收支 ${Formatters.money(statement.incomeMinor - statement.expenseMinor)} · " +
                    "交易 ${statement.transactions.size} 笔",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (statement.anomalyCandidates.isNotEmpty()) {
                Text(
                    text = "异常候选 ${statement.anomalyCandidates.size} 笔：${statement.anomalyRule}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 澄清候选（§3.12 候选选择区 / B-62）：点击后回填输入框，不自动发送。 */
@Composable
internal fun ClarificationCard(holder: ChatStateHolder) {
    val question = holder.clarificationQuestion
    val candidates = holder.recipientCandidates
    if (question.isNullOrBlank() && candidates.isEmpty()) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (!question.isNullOrBlank()) {
                Text(text = question, fontSize = 12.sp)
            }
            candidates.forEach { candidate ->
                TextButton(
                    onClick = { holder.fillCandidate(candidate) },
                    enabled = !holder.busy,
                ) {
                    Text(
                        text = "${candidate.name} · ${candidate.note} · ${candidate.label}",
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** 建议 chips（§3.11 / B-10..B-14）。 */
@Composable
internal fun SuggestionRow(enabled: Boolean, onSend: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SUGGESTIONS.forEach { (label, actual) ->
            AssistChip(
                onClick = { onSend(actual) },
                enabled = enabled,
                label = { Text(text = label, fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 输入区（§4.1）：多行输入 + 发送；忙碌时禁用发送。 */
@Composable
internal fun ChatInputArea(holder: ChatStateHolder, focusRequester: FocusRequester) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = holder.input,
                onValueChange = { value -> holder.onInputChange(value) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text(INPUT_PLACEHOLDER, fontSize = 13.sp) },
                minLines = 2,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { holder.sendInput() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { holder.sendInput() },
                enabled = !holder.busy && holder.input.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Text(if (holder.sending) "发送中" else "发送", fontSize = 13.sp)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                text = INPUT_HINT,
                modifier = Modifier.weight(1f),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${holder.input.length}/$MAX_INPUT_CHARS",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 状态行（B-18/B-19/B-23）。 */
@Composable
internal fun ChatStatusLine(holder: ChatStateHolder) {
    val line = holder.statusLine ?: return
    Text(
        text = line,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 错误横幅：业务 code + message + 处置（「核对原请求」用同一个 requestId）。 */
@Composable
internal fun ChatBannerArea(holder: ChatStateHolder) {
    val banner = holder.banner ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = banner.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
            banner.hint?.let { hint ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (holder.pendingOriginal != null) {
                    TextButton(onClick = { holder.retryOriginal() }, enabled = !holder.sending) {
                        Text("核对原请求", fontSize = 12.sp)
                    }
                }
                if (holder.sessionError != null) {
                    TextButton(onClick = { holder.refreshSession() }, enabled = !holder.bootstrapping) {
                        Text("重新建立会话", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { holder.dismissBanner() }) {
                    Text("关闭", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 待确认操作区标题（对话流内联）。 */
@Composable
internal fun PendingActionsHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "待确认操作",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "（$count 项 · 聊天中的确认不会执行操作）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun JsonElement.resultTool(): String? {
    if (!isJsonObject) return null
    val element = asJsonObject.get("tool") ?: return null
    if (!element.isJsonPrimitive) return null
    return runCatching { element.asString }.getOrNull()
}
