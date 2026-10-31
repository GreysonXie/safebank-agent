package cn.safebank.app.ui.screens.mandates

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.MandateVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.screens.more.EmptyStateBlock
import cn.safebank.app.ui.screens.more.InfoBanner
import cn.safebank.app.ui.screens.more.LoadingRow
import cn.safebank.app.ui.screens.more.PageTitle
import cn.safebank.app.ui.screens.more.PageToast
import cn.safebank.app.ui.screens.more.PendingActionCard
import cn.safebank.app.ui.screens.more.PendingActionFlow
import cn.safebank.app.ui.screens.more.PrimaryActionButton
import cn.safebank.app.ui.screens.more.SecondaryActionButton
import cn.safebank.app.ui.screens.more.SectionCard
import cn.safebank.app.ui.screens.more.StatusBadge
import cn.safebank.app.ui.screens.more.Tone
import cn.safebank.app.ui.screens.more.friendlyError
import cn.safebank.app.ui.screens.more.intervalLabel
import cn.safebank.app.ui.screens.more.mandateStatusLabel
import cn.safebank.app.ui.screens.more.mandateStatusTone
import cn.safebank.app.ui.screens.more.moneyOrDash
import kotlinx.coroutines.launch

/**
 * 「代扣管理」tab（ui-spec §4.3）：
 *
 * 读取代扣只有一个入口：确认查询范围 → QUERY_MANDATES 预览 → 授权 → 提交 → 回执 data 列表。
 * `GET /api/mandates` 恒返回 409「QUERY_CONFIRMATION_REQUIRED」，本页**从不**直接调用它。
 * 撤销同样走预览 → 授权 → 提交，并明确「只撤销银行侧授权，商户合同状态未知」。
 */
@Composable
fun MandatesRoute(navigator: AppNavigator, container: AppContainer) {
    val scope = rememberCoroutineScope()
    val repository = container.repository
    var toast by remember { mutableStateOf<String?>(null) }
    val flow = remember { PendingActionFlow(scope, repository) { message -> toast = message } }

    /** null = 尚未查询（渲染「确认查询范围」）；空列表 = 查询成功但无数据。 */
    var mandates by remember { mutableStateOf<List<MandateVo>?>(null) }
    var queryingIntent by remember { mutableStateOf(false) }
    var revokingId by remember { mutableStateOf<String?>(null) }
    var errorBanner by remember { mutableStateOf<String?>(null) }
    var guideNote by remember { mutableStateOf<String?>(null) }

    // 回执落地：查询成功 → 展示列表；撤销成功 → 本地把该行标记为已撤销。
    LaunchedEffect(flow.receipt) {
        val result = flow.receipt ?: return@LaunchedEffect
        when (result.kind) {
            "QUERY_MANDATES" -> {
                val data = result.data
                if (data != null) {
                    mandates = data
                    guideNote = "查询已确认。可继续分析，或先生成撤销预览；预览不会改变授权。" +
                        "如需继续分析，请切换到「对话助手」提问。"
                    toast = "代扣查询已确认，可继续查看收费周期或生成撤销预览。"
                } else {
                    guideNote = "这是原查询回执，重新查询需要再次确认范围。"
                    toast = "这是原查询回执，重新查询需再次确认范围。"
                }
                flow.dismiss()
            }

            "REVOKE" -> {
                if (result.status == "VERIFIED_SUCCESS") {
                    val mandateId = flow.action?.mandateId
                    mandates = mandates?.map { mandate ->
                        if (mandate.id == mandateId) mandate.copy(status = "REVOKED") else mandate
                    }
                    guideNote = null
                    toast = "银行侧代扣已撤销，商户合同状态未知，余额没有因此增加。"
                    flow.dismiss()
                }
            }
        }
    }

    val startQueryIntent: () -> Unit = {
        if (!queryingIntent) {
            scope.launch {
                queryingIntent = true
                errorBanner = null
                repository.createQueryIntent()
                    .onSuccess { action ->
                        flow.begin(action)
                        toast = "已生成代扣查询意图，请核对上方待确认操作卡，勾选确认后提交。"
                    }
                    .onFailure { error -> errorBanner = friendlyError(error) }
                queryingIntent = false
            }
        }
    }

    val previewRevoke: (MandateVo) -> Unit = { mandate ->
        scope.launch {
            revokingId = mandate.id
            errorBanner = null
            repository.previewMandateRevoke(mandate.id)
                .onSuccess { action ->
                    flow.begin(action)
                    toast = "已生成撤销预览，请核对上方待确认操作卡后确认。"
                }
                .onFailure { error -> errorBanner = friendlyError(error) }
            revokingId = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PageTitle(title = "代扣管理", subtitle = "核对授权与扣费周期，撤销前逐项确认。")

            val banner = errorBanner
            if (banner != null) {
                InfoBanner(text = banner, tone = Tone.Danger)
                Spacer(modifier = Modifier.height(6.dp))
                SecondaryActionButton(text = "关闭提示", onClick = { errorBanner = null })
            }

            if (flow.action != null) {
                PendingActionCard(flow = flow)
            }

            SectionCard(
                title = "银行侧代扣",
                subtitle = "仅撤销银行侧扣款授权；不会自动退款，商户合同状态未知。",
            ) {
                val currentMandates = mandates
                if (currentMandates == null) {
                    Text(
                        text = "读取本人银行侧代扣列表前，请确认查询范围。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoBanner(
                        text = "银行侧代扣列表不能直接读取（GET /api/mandates 恒返回 409）；" +
                            "必须先生成 QUERY_MANDATES 查询意图，勾选确认并提交后由回执返回列表。",
                        tone = Tone.Neutral,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (queryingIntent) {
                        LoadingRow("正在生成查询意图…")
                    } else {
                        PrimaryActionButton(text = "确认查询范围", onClick = startQueryIntent)
                    }
                } else {
                    val note = guideNote
                    if (note != null) {
                        InfoBanner(text = note, tone = Tone.Success)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (currentMandates.isEmpty()) {
                        EmptyStateBlock(
                            title = "本次查询没有返回银行侧代扣授权。",
                            hint = "可以点击下方「重新查询」再次确认范围。",
                        )
                    } else {
                        currentMandates.forEach { mandate ->
                            MandateRow(
                                mandate = mandate,
                                revoking = revokingId == mandate.id,
                                onPreviewRevoke = { previewRevoke(mandate) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryActionButton(
                        text = "重新查询",
                        onClick = startQueryIntent,
                        loading = queryingIntent,
                    )
                }
            }

            Text(
                text = "提示：撤销预览本身不会改变授权；只有提交成功且回执 VERIFIED_SUCCESS 才表示银行侧授权已撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
        PageToast(message = toast) { toast = null }
    }
}

@Composable
private fun MandateRow(mandate: MandateVo, revoking: Boolean, onPreviewRevoke: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mandate.merchant.ifBlank { mandate.id },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = mandateStatusLabel(mandate.status),
                    tone = mandateStatusTone(mandate.status),
                )
            }
            Text(
                text = "${moneyOrDash(mandate.amountMinor)} · ${intervalLabel(mandate.billingInterval)} · " +
                    mandateStatusLabel(mandate.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "续费日：${Formatters.shortDate(mandate.renewalDate)} · 商户合同状态未知" +
                    "（银行侧撤销不代表商户合同终止）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (mandate.status.equals("ACTIVE", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(6.dp))
                SecondaryActionButton(
                    text = "预览撤销",
                    onClick = onPreviewRevoke,
                    loading = revoking,
                )
            } else {
                Text(
                    text = "银行侧授权已撤销，不可重复撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
