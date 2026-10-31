package cn.safebank.app.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.ChatResponse
import cn.safebank.app.data.model.ConversationVo
import cn.safebank.app.data.model.SessionVo
import cn.safebank.app.data.model.StatementVo
import cn.safebank.app.ui.screens.actions.ActionCardState
import cn.safebank.app.ui.screens.actions.apiCode
import cn.safebank.app.ui.screens.actions.codeHint
import cn.safebank.app.ui.screens.actions.failureHint
import cn.safebank.app.ui.screens.actions.failureText
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** 对话消息（气泡）。 */
enum class ChatRole { USER, ASSISTANT }

data class ChatMessageUi(
    val role: ChatRole,
    val text: String,
    val welcome: Boolean = false,
)

/** 错误横幅：业务 code + 中文 message + 处置提示。 */
data class ChatBanner(
    val message: String,
    val code: String?,
    val hint: String?,
)

/** 未完成的原请求：只有用户点「核对原请求」才会用同一个 requestId 重发（不自动重放）。 */
data class PendingTurn(
    val text: String,
    val requestId: String,
)

/** 本轮「查询依据」（§3.4 / §3.9 / B-25）。 */
data class EvidenceUi(
    val trace: List<String>,
    val results: List<JsonElement>,
    val answerSource: String?,
    val modelUsage: JsonElement?,
    val timing: JsonElement?,
    val statement: StatementVo?,
    val factReferences: List<String>,
)

/**
 * 对话页状态机（契约 §4.29–§4.32、§6.6；ui-design-spec §3.12 / §4.1）。
 *
 * 关键安全语义：
 * - 对话请求**不自动重发**：失败后保留原文本 + requestId，由用户点「核对原请求」重发；
 * - `conversationId` / `contextVersion` 每次响应后更新并回传，版本冲突时重新拉取上下文；
 * - 待确认操作卡交给 [ActionCardState]（同一套逐动作授权流程）。
 */
class ChatStateHolder(private val repository: SafeBankRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---------------- 会话 / 上下文 ----------------
    var session by mutableStateOf<SessionVo?>(null)
        private set
    var bootstrapping by mutableStateOf(true)
        private set
    var sessionError by mutableStateOf<String?>(null)
        private set

    var conversation by mutableStateOf<ConversationVo?>(null)
        private set
    var conversationId by mutableStateOf<String?>(null)
        private set
    var contextVersion by mutableStateOf(0)
        private set

    // ---------------- 消息流 ----------------
    val messages = mutableStateListOf<ChatMessageUi>()
    val actionCards = mutableStateListOf<ActionCardState>()

    var input by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    var resetting by mutableStateOf(false)
        private set
    var statusLine by mutableStateOf<String?>(null)
        private set
    var banner by mutableStateOf<ChatBanner?>(null)
        private set
    var pendingOriginal by mutableStateOf<PendingTurn?>(null)
        private set

    var evidence by mutableStateOf<EvidenceUi?>(null)
        private set
    var evidenceExpanded by mutableStateOf(false)
    var usageExpanded by mutableStateOf(false)

    var clarificationQuestion by mutableStateOf<String?>(null)
        private set
    var recipientCandidates by mutableStateOf<List<RecipientCandidate>>(emptyList())
        private set

    /** 递增后在 UI 里请求输入框焦点（点候选收款人后回填）。 */
    var focusTick by mutableStateOf(0)
        private set

    val hasConversation: Boolean get() = conversationId != null
    val busy: Boolean get() = sending || resetting

    init {
        messages.add(ChatMessageUi(ChatRole.ASSISTANT, WELCOME_TEXT, welcome = true))
        scope.launch { bootstrap() }
    }

    // ------------------------------------------------------------ 会话

    private suspend fun bootstrap() {
        bootstrapping = true
        repository.ensureSession().fold(
            onSuccess = { sessionVo ->
                session = sessionVo
                sessionError = null
                tryCreateConversation()
            },
            onFailure = { failure ->
                session = null
                sessionError = failureText(failure)
            },
        )
        bootstrapping = false
    }

    /** 顶部「刷新」：重建会话 + 重新同步/新建上下文。 */
    fun refreshSession() {
        if (bootstrapping || sending) return
        scope.launch {
            bootstrapping = true
            sessionError = null
            statusLine = "正在恢复业务会话，请稍候。"
            repository.ensureSession(force = true).fold(
                onSuccess = { sessionVo ->
                    session = sessionVo
                    val id = conversationId
                    if (id == null) {
                        tryCreateConversation()
                    } else {
                        repository.conversation(id).fold(
                            onSuccess = { applyConversation(it) },
                            onFailure = {
                                clearConversation()
                                tryCreateConversation()
                            },
                        )
                    }
                    statusLine = null
                },
                onFailure = { failure ->
                    session = null
                    sessionError = failureText(failure)
                    statusLine = null
                    banner = ChatBanner(
                        message = "初始化失败：${failureText(failure)}",
                        code = failure.apiCode(),
                        hint = failureHint(failure),
                    )
                },
            )
            bootstrapping = false
        }
    }

    private suspend fun tryCreateConversation() {
        // 首次提问时 /api/chat 也会自动建立上下文；这里提前建，便于上下文条立即有状态。
        repository.createConversation().fold(
            onSuccess = { applyConversation(it) },
            onFailure = { /* 忽略：不影响后续提问，/api/chat 会自动建立 */ },
        )
    }

    private fun applyConversation(conversationVo: ConversationVo) {
        conversation = conversationVo
        if (conversationVo.conversationId.isNotBlank()) {
            conversationId = conversationVo.conversationId
        }
        contextVersion = conversationVo.version
    }

    private fun clearConversation() {
        conversation = null
        conversationId = null
        contextVersion = 0
    }

    // ------------------------------------------------------------ 发送

    fun onInputChange(value: String) {
        input = value.take(MAX_INPUT_CHARS)
    }

    fun sendInput() {
        if (busy) {
            statusLine = STATUS_BUSY_BLOCK
            return
        }
        val text = input.trim()
        if (text.isEmpty()) return
        if (text.length > MAX_INPUT_CHARS) {
            banner = ChatBanner("问题超长：最多 $MAX_INPUT_CHARS 字，请精简后重试。", null, null)
            return
        }
        input = ""
        messages.add(ChatMessageUi(ChatRole.USER, text))
        dispatch(text, UUID.randomUUID().toString())
    }

    fun sendSuggestion(actualText: String) {
        if (busy) {
            statusLine = STATUS_BUSY_BLOCK
            return
        }
        if (actualText.isBlank()) return
        input = ""
        messages.add(ChatMessageUi(ChatRole.USER, actualText))
        dispatch(actualText, UUID.randomUUID().toString())
    }

    /** 「核对原请求」：用同一个 requestId 重发原始文本（仅此一次，不自动重放）。 */
    fun retryOriginal() {
        val pending = pendingOriginal ?: return
        if (busy) {
            statusLine = STATUS_BUSY_BLOCK
            return
        }
        dispatch(pending.text, pending.requestId)
    }

    fun dismissBanner() {
        banner = null
    }

    fun toggleEvidence() {
        evidenceExpanded = !evidenceExpanded
    }

    fun fillCandidate(candidate: RecipientCandidate) {
        input = "选择收款人 ${candidate.name}，备注 ${candidate.note}"
        focusTick += 1
    }

    private fun dispatch(text: String, requestId: String) {
        if (sending) return
        sending = true
        statusLine = STATUS_THINKING
        banner = null
        pendingOriginal = null
        scope.launch {
            val result = repository.chat(
                text = text,
                conversationId = conversationId,
                expectedContextVersion = contextVersion.takeIf { it > 0 },
                requestId = requestId,
            )
            result.fold(
                onSuccess = { response -> applyChatResponse(response) },
                onFailure = { failure -> handleChatFailure(text, requestId, failure) },
            )
            sending = false
        }
    }

    private fun applyChatResponse(response: ChatResponse) {
        statusLine = null
        banner = null
        pendingOriginal = null

        val context = response.context
        if (context != null) {
            applyConversation(context)
        } else {
            if (response.conversationId.isNotBlank()) conversationId = response.conversationId
            if (response.contextVersion > 0) contextVersion = response.contextVersion
        }

        if (response.text.isNotBlank()) {
            messages.add(ChatMessageUi(ChatRole.ASSISTANT, response.text))
        }

        evidence = EvidenceUi(
            trace = response.trace,
            results = response.results.orEmpty(),
            answerSource = response.answerSource,
            modelUsage = response.modelUsage,
            timing = response.timing,
            statement = parseStatement(response.data),
            factReferences = response.factReferences.orEmpty(),
        )

        // 待确认操作卡：同一 action id 只保留一张（新卡置顶替换）。
        response.actions.filter { it.isAwaitingAuth }.forEach { action ->
            val existing = actionCards.indexOfFirst { it.id == action.id }
            val card = ActionCardState(scope, repository, action)
            if (existing >= 0) {
                actionCards[existing] = card
            } else {
                actionCards.add(0, card)
            }
        }

        // clarification 有三种形态：null / {"required":bool} / ClarificationVO
        val question = response.clarificationQuestion()
        clarificationQuestion = question ?: if (
            response.clarification?.isJsonObject == true &&
            response.clarification.jsonBoolean("required") == true
        ) {
            "需要补充关键信息（月份 / 收款对象 / 金额）后才能继续。"
        } else {
            null
        }
        recipientCandidates = parseRecipientCandidates(response.clarification)
    }

    private fun handleChatFailure(text: String, requestId: String, failure: Throwable) {
        statusLine = null
        val code = failure.apiCode()
        when (code) {
            "CONTEXT_EXPIRED", "NOT_FOUND" -> {
                clearConversation()
                pendingOriginal = null
                messages.add(ChatMessageUi(ChatRole.ASSISTANT, STATUS_CONTEXT_INVALID))
                banner = ChatBanner(failureText(failure), code, failureHint(failure))
            }
            "CONFLICT" -> {
                pendingOriginal = PendingTurn(text, requestId)
                banner = ChatBanner(failureText(failure), code, failureHint(failure))
                scope.launch {
                    val id = conversationId ?: return@launch
                    repository.conversation(id).fold(
                        onSuccess = { applyConversation(it) },
                        onFailure = { clearConversation() },
                    )
                }
            }
            "CONTEXT_PROCESSING", "AGENT_RECONCILING", "CONTEXT_INTERRUPTED" -> {
                pendingOriginal = PendingTurn(text, requestId)
                statusLine = STATUS_LONG_WAIT
                banner = ChatBanner(failureText(failure), code, failureHint(failure))
            }
            else -> {
                pendingOriginal = PendingTurn(text, requestId)
                banner = ChatBanner(failureText(failure), code, codeHint(code) ?: failureHint(failure))
            }
        }
    }

    // ------------------------------------------------------------ 清空草案

    fun resetDraft() {
        val id = conversationId
        if (id == null) {
            statusLine = "当前还没有对话上下文，先提一个问题即可建立。"
            return
        }
        if (busy) return
        resetting = true
        statusLine = "正在清空对话草案…"
        scope.launch {
            repository.resetConversation(id, contextVersion).fold(
                onSuccess = { conversationVo ->
                    applyConversation(conversationVo)
                    messages.clear()
                    messages.add(ChatMessageUi(ChatRole.ASSISTANT, STATUS_RESET_DONE))
                    evidence = null
                    evidenceExpanded = false
                    banner = null
                    pendingOriginal = null
                    clarificationQuestion = null
                    recipientCandidates = emptyList()
                    statusLine = null
                },
                onFailure = { failure ->
                    statusLine = null
                    banner = ChatBanner(failureText(failure), failure.apiCode(), failureHint(failure))
                    if (failure.apiCode() == "CONFLICT") {
                        scope.launch {
                            repository.conversation(id).fold(
                                onSuccess = { applyConversation(it) },
                                onFailure = { clearConversation() },
                            )
                        }
                    }
                },
            )
            resetting = false
        }
    }
}

/**
 * 对话页 holder 的进程级缓存：底部 tab 切换会销毁 Composable，
 * 但对话草稿与待确认卡不应丢失（Web 端也是跨面板保留），因此在这里保留单例。
 */
object ChatScreens {
    private var cached: ChatStateHolder? = null

    fun of(repository: SafeBankRepository): ChatStateHolder =
        cached ?: ChatStateHolder(repository).also { cached = it }
}
