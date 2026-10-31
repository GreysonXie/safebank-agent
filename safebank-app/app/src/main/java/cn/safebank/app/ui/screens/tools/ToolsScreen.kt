package cn.safebank.app.ui.screens.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.RecipientVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.screens.more.DetailPageScaffold
import cn.safebank.app.ui.screens.more.DropdownField
import cn.safebank.app.ui.screens.more.EmptyStateBlock
import cn.safebank.app.ui.screens.more.FormField
import cn.safebank.app.ui.screens.more.InfoBanner
import cn.safebank.app.ui.screens.more.LoadingRow
import cn.safebank.app.ui.screens.more.PageToast
import cn.safebank.app.ui.screens.more.PendingActionCard
import cn.safebank.app.ui.screens.more.PendingActionFlow
import cn.safebank.app.ui.screens.more.PrimaryActionButton
import cn.safebank.app.ui.screens.more.SecondaryActionButton
import cn.safebank.app.ui.screens.more.SectionCard
import cn.safebank.app.ui.screens.more.SelectableRow
import cn.safebank.app.ui.screens.more.Tone
import cn.safebank.app.ui.screens.more.friendlyError
import kotlinx.coroutines.launch

/**
 * 「准备一笔转账」二级页（ui-spec §4.5 / §3.9.1）：
 * 账户 + 收款人（必须由用户显式选择，重名不自动猜选）+ 金额 → 预览 → 授权 → 提交 → 查单。
 * 预览与授权/提交严格两步，不做合并。
 */
@Composable
fun ToolsRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val repository = container.repository
    var toast by remember { mutableStateOf<String?>(null) }
    val flow = remember { PendingActionFlow(scope, repository) { message -> toast = message } }

    var accounts by remember { mutableStateOf<List<AccountVo>>(emptyList()) }
    var accountsLoading by remember { mutableStateOf(true) }
    var accountsError by remember { mutableStateOf<String?>(null) }
    var accountsTick by remember { mutableStateOf(0) }
    var sourceId by remember { mutableStateOf<String?>(null) }

    var recipientQuery by remember { mutableStateOf("张三") }
    var recipients by remember { mutableStateOf<List<RecipientVo>>(emptyList()) }
    var recipientsQueried by remember { mutableStateOf(false) }
    var recipientLoading by remember { mutableStateOf(false) }
    var selectedRecipientId by remember { mutableStateOf<String?>(null) }

    var amountText by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }

    LaunchedEffect(accountsTick) {
        accountsLoading = true
        accountsError = null
        repository.accounts()
            .onSuccess { list ->
                accounts = list
                if (sourceId == null || list.none { it.id == sourceId }) {
                    sourceId = list.firstOrNull()?.id
                }
            }
            .onFailure { error -> accountsError = friendlyError(error) }
        accountsLoading = false
    }

    // 转账成功后刷新账户余额（submit 已真实动账）。
    LaunchedEffect(flow.receipt) {
        val result = flow.receipt ?: return@LaunchedEffect
        if (result.kind == "TRANSFER" && result.status == "VERIFIED_SUCCESS") {
            accountsTick += 1
        }
    }

    val searchRecipients: () -> Unit = {
        val query = recipientQuery.trim()
        if (query.isEmpty()) {
            formError = "请输入收款人姓名、备注或编号后再查询。"
            recipients = emptyList()
            recipientsQueried = false
            selectedRecipientId = null
        } else {
            scope.launch {
                recipientLoading = true
                formError = null
                recipients = emptyList()
                recipientsQueried = true
                selectedRecipientId = null
                repository.recipients(query)
                    .onSuccess { list ->
                        recipients = list
                        if (list.isEmpty()) {
                            formError = "没有找到「$query」对应的收款人，请确认姓名或备注（精确匹配）。"
                        }
                    }
                    .onFailure { error -> formError = friendlyError(error) }
                recipientLoading = false
            }
        }
    }

    val generatePreview: () -> Unit = preview@{
        val source = sourceId
        val recipient = selectedRecipientId
        val trimmed = amountText.trim()
        val amount = Formatters.parseAmountToMinor(trimmed)
        when {
            source == null -> {
                formError = "请选择来源账户。"
                return@preview
            }

            recipient == null -> {
                formError = "请先查询并明确选择收款人（重名联系人不会自动猜选）。"
                return@preview
            }

            trimmed.isEmpty() -> {
                formError = "请输入转账金额。"
                return@preview
            }

            amount == null -> {
                formError = "金额无效：请输入最多两位小数的数字。"
                return@preview
            }

            amount <= 0L -> {
                formError = "金额最多两位小数，且必须为正数。"
                return@preview
            }

            amount > 1_000_000L -> {
                formError = "演示单笔限额 ¥10,000，请调整金额。"
                return@preview
            }
        }
        formError = null
        scope.launch {
            previewing = true
            repository.previewTransfer(source, recipient, amount)
                .onSuccess { action ->
                    flow.begin(action)
                    toast = "转账预览已生成，请在下方核对后勾选确认。"
                }
                .onFailure { error -> formError = friendlyError(error) }
            previewing = false
        }
    }

    val selectedRecipient = recipients.firstOrNull { it.id == selectedRecipientId }

    Box(modifier = Modifier.fillMaxSize()) {
        DetailPageScaffold(navigator = navigator, title = "准备一笔转账") {
            Text(
                text = "填写对象与金额，先生成操作预览；预览不会执行，确认后还要单独授权与提交。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            SectionCard(
                title = "准备一笔转账",
                subtitle = "演示单笔限额 ¥10,000。重名联系人不会由系统自动猜选。",
            ) {
                if (accountsLoading) {
                    LoadingRow("正在读取账户…")
                } else if (accountsError != null) {
                    InfoBanner(text = accountsError.orEmpty(), tone = Tone.Danger)
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(text = "重新读取账户", onClick = { accountsTick += 1 })
                } else {
                    DropdownField(
                        label = "来源账户",
                        options = accounts.map { account ->
                            account.id to "${account.label}（余额 ${Formatters.money(account.balanceMinor)}）"
                        },
                        selectedKey = sourceId,
                        onSelect = { key -> sourceId = key },
                    )
                }

                FormField(
                    label = "收款人（姓名 / 备注 / 编号）",
                    value = recipientQuery,
                    onValueChange = { input ->
                        recipientQuery = input
                        recipientsQueried = false
                        recipients = emptyList()
                        selectedRecipientId = null
                    },
                    placeholder = "张三",
                    hint = "精确匹配姓名、备注、编号或账户标签（如 zhang-a / travel-recipient）。",
                )
                SecondaryActionButton(
                    text = "查询收款人",
                    onClick = searchRecipients,
                    loading = recipientLoading,
                )

                if (recipientsQueried) {
                    if (recipients.isEmpty()) {
                        EmptyStateBlock(
                            title = "没有匹配的收款人",
                            hint = "请确认姓名、备注或编号后重新查询。",
                        )
                    } else {
                        Text(
                            text = "请选择收款人（重名不会自动选择）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        recipients.forEach { recipient ->
                            val subtitle = listOfNotNull(
                                recipient.label.takeIf { it.isNotBlank() },
                                recipient.note.takeIf { it.isNotBlank() },
                                "编号 ${recipient.id}",
                            ).joinToString(" · ")
                            SelectableRow(
                                title = recipient.name.ifBlank { recipient.id },
                                subtitle = subtitle,
                                selected = recipient.id == selectedRecipientId,
                                onClick = {
                                    selectedRecipientId = recipient.id
                                    formError = null
                                },
                            )
                        }
                    }
                }

                if (selectedRecipient != null) {
                    InfoBanner(
                        text = "已选收款人：${selectedRecipient.name} · ${selectedRecipient.label}" +
                            "（编号 ${selectedRecipient.id}）",
                        tone = Tone.Info,
                    )
                }

                FormField(
                    label = "金额（人民币元）",
                    value = amountText,
                    onValueChange = { input ->
                        amountText = input
                        formError = null
                    },
                    placeholder = "500.00",
                    keyboardType = KeyboardType.Decimal,
                    hint = "支持最多两位小数；单笔上限 ¥10,000。",
                )

                val currentFormError = formError
                if (currentFormError != null) {
                    InfoBanner(text = currentFormError, tone = Tone.Danger)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                PrimaryActionButton(
                    text = "生成转账预览",
                    onClick = generatePreview,
                    loading = previewing,
                    enabled = !previewing,
                )
            }

            PendingActionCard(
                flow = flow,
                sourceLabel = accounts.firstOrNull { it.id == flow.action?.sourceId }?.label,
            )
        }
        PageToast(message = toast) { toast = null }
    }
}
