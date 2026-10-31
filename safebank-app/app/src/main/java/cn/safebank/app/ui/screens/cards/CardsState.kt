package cn.safebank.app.ui.screens.cards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.safebank.app.data.SafeBankRepository
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.BankCardVo
import cn.safebank.app.data.model.CardChangeRequest
import cn.safebank.app.data.model.CardOperationVo
import cn.safebank.app.data.network.ApiException
import cn.safebank.app.data.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 卡片管理页的读写状态机（t020 契约：`POST /api/cards/preview` → `.../confirm` 或 `.../cancel`）。
 *
 * 关键约束（后端 `CardManagementService`）：
 * - 操作状态只有 `AWAITING_CONFIRMATION` / `COMPLETED` / `CANCELLED`，没有 `AWAITING_AUTH`；
 * - 确认必须携带同一份预览的 `payloadHash`（repository 会固定 `confirmed=true`）；
 * - `UPDATE_CONTROLS` 必须同时给 `onlineEnabled` 与 `overseasEnabled`（由调用方构造 request 时保证）；
 * - 预览本身不改变卡片，只有确认成功才生效；预览 10 分钟过期。
 *
 * 所有失败都转成中文文案放进 [actionError] / [cardsError] / [opsError]，绝不静默吞掉。
 */
internal class CardsStateHolder(
    private val scope: CoroutineScope,
    private val repository: SafeBankRepository,
) {
    var cards by mutableStateOf<List<BankCardVo>>(emptyList())
        private set
    var operations by mutableStateOf<List<CardOperationVo>>(emptyList())
        private set
    var accounts by mutableStateOf<List<AccountVo>>(emptyList())
        private set

    var loadingCards by mutableStateOf(false)
        private set
    var loadingOps by mutableStateOf(false)
        private set
    var loadingAccounts by mutableStateOf(false)
        private set

    var cardsError by mutableStateOf<String?>(null)
        private set
    var opsError by mutableStateOf<String?>(null)
        private set
    var accountsError by mutableStateOf<String?>(null)
        private set

    /** 预览 / 确认 / 取消失败的中文提示（页面顶部展示）。 */
    var actionError by mutableStateOf<String?>(null)
        private set

    /** 操作成功后的中文提示（可关闭的成功横幅）。 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 有请求在途时禁用全部操作按钮，避免重复预览 / 重复确认。 */
    var actionBusy by mutableStateOf(false)
        private set

    /** 当前待确认的预览。为空表示没有由本页发起的待确认项。 */
    var pending by mutableStateOf<CardOperationVo?>(null)
        private set

    /** 未挂失卡片（后端 APPLY 的 8 张上限只统计非 LOST 卡）。 */
    val activeCards: List<BankCardVo>
        get() = cards.filter { it.status != "LOST" }

    fun reload() {
        loadCards()
        loadOperations()
    }

    fun loadCards() {
        if (loadingCards) return
        loadingCards = true
        cardsError = null
        scope.launch {
            repository.cards().fold(
                onSuccess = { cards = it },
                onFailure = { cardsError = cardFailureText(it) },
            )
            loadingCards = false
        }
    }

    fun loadOperations() {
        if (loadingOps) return
        loadingOps = true
        opsError = null
        scope.launch {
            repository.cardOperations().fold(
                onSuccess = { operations = it },
                onFailure = { opsError = cardFailureText(it) },
            )
            loadingOps = false
        }
    }

    fun loadAccounts() {
        if (loadingAccounts) return
        loadingAccounts = true
        accountsError = null
        scope.launch {
            repository.accounts().fold(
                onSuccess = { accounts = it },
                onFailure = { accountsError = cardFailureText(it) },
            )
            loadingAccounts = false
        }
    }

    fun dismissPending() {
        pending = null
    }

    fun clearNotice() {
        notice = null
    }

    fun clearActionError() {
        actionError = null
    }

    /**
     * 生成一份预览。成功时把结果放进 [pending]，由页面渲染「待确认变更」卡片。
     *
     * @param successNotice 预览成功后的中文提示
     */
    fun preview(request: CardChangeRequest, successNotice: String) {
        if (actionBusy) return
        actionBusy = true
        actionError = null
        scope.launch {
            repository.previewCardChange(request).fold(
                onSuccess = {
                    pending = it
                    notice = successNotice
                },
                onFailure = { actionError = cardFailureText(it) },
            )
            actionBusy = false
        }
    }

    /** 确认（提交）一份预览；成功后刷新卡片列表与操作历史。 */
    fun confirm(operation: CardOperationVo) {
        if (actionBusy) return
        actionBusy = true
        actionError = null
        scope.launch {
            repository.confirmCardOperation(operation.id, operation.payloadHash).fold(
                onSuccess = { completed ->
                    if (pending?.id == operation.id) pending = null
                    val suffix = completed.card?.maskedNumber?.let { "（$it）" }.orEmpty()
                    notice = "已完成：${Formatters.cardOperationKindLabel(completed.kind)}$suffix"
                    reload()
                },
                onFailure = { failure ->
                    // 预览过期 / 卡片状态已变：本地待确认项已不可用，清掉并刷新卡片与历史。
                    val api = failure as? ApiException
                    if ((api?.isAuthExpired == true || api?.isConflict == true) && pending?.id == operation.id) {
                        pending = null
                        reload()
                    }
                    actionError = cardFailureText(failure)
                },
            )
            actionBusy = false
        }
    }

    /** 取消一份预览；卡片不会发生变化。 */
    fun cancel(operation: CardOperationVo) {
        if (actionBusy) return
        actionBusy = true
        actionError = null
        scope.launch {
            repository.cancelCardOperation(operation.id).fold(
                onSuccess = {
                    if (pending?.id == operation.id) pending = null
                    notice = "已取消本次卡片变更，卡片未发生变化。"
                    reload()
                },
                onFailure = { actionError = cardFailureText(it) },
            )
            actionBusy = false
        }
    }
}

/**
 * 数据层失败 → 中文可读文案：优先使用 `ApiException.message`（后端已是中文），
 * 再按 code / 网络状态补充处置提示；非 [ApiException] 也给出兜底中文，绝不显示空字符串。
 */
internal fun cardFailureText(failure: Throwable): String {
    val api = failure as? ApiException
    if (api == null) {
        return failure.message?.trim()?.takeIf { it.isNotBlank() } ?: "请求未完成，请稍后重试。"
    }
    val message = api.message.trim().takeIf { it.isNotBlank() } ?: "请求未完成，请稍后重试。"
    return when {
        api.isNetworkError ->
            "无法连接服务器：$message 请确认后端已启动，并在「设置」核对服务器地址（模拟器使用 http://10.0.2.2:8080）。"
        api.isSessionError ->
            "会话已失效：$message 请刷新页面后重试；若仍失败请到「设置」检查服务器地址。"
        api.code.isBlank() -> message
        else -> "$message（${api.code}）"
    }
}
