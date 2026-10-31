package cn.safebank.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.safebank.app.ui.theme.SafeBankColors

/**
 * 状态徽章与提示条（ui-design-spec §1.4、§3.8、§6.1 扩展色）。
 *
 * 无障碍要求：**状态文案永远与颜色同时出现**（不要只靠色差传意，§7.5）。
 */

/** 业务状态徽章色调（对应 §1.4 的 6 套底色 + 2 个回落默认蓝）。 */
enum class BadgeTone {
    /** 默认蓝底（CANCEL_REQUESTED / PARTIAL_SUCCESS / 未知状态） */
    Default,

    /** 黄底：AWAITING_AUTH / PENDING / UNKNOWN */
    Warning,

    /** 蓝底：AUTHORIZED */
    Authorized,

    /** 绿底：VERIFIED_SUCCESS */
    Success,

    /** 红底：VERIFIED_FAILED */
    Danger,

    /** 灰底：CANCELLED */
    Neutral,
}

/** 动作/任务状态 → 徽章色调的默认映射（文案仍由调用方给，保持与 Formatters 一致）。 */
fun actionStatusBadgeTone(status: String?): BadgeTone = when (status) {
    "AWAITING_AUTH", "PENDING", "UNKNOWN" -> BadgeTone.Warning
    "AUTHORIZED" -> BadgeTone.Authorized
    "VERIFIED_SUCCESS" -> BadgeTone.Success
    "VERIFIED_FAILED" -> BadgeTone.Danger
    "CANCELLED" -> BadgeTone.Neutral
    else -> BadgeTone.Default
}

/**
 * 状态徽章：12sp、4dp 圆角、内边距 4×8（§3.8）。
 *
 * @param text 必须给完整中文语义（如「待确认 · 尚未执行」），不要只给状态码
 */
@Composable
fun StatusBadge(
    text: String,
    tone: BadgeTone = BadgeTone.Default,
    modifier: Modifier = Modifier,
) {
    val (background, foreground) = when (tone) {
        BadgeTone.Default -> SafeBankColors.statusDefaultBg to SafeBankColors.statusDefaultFg
        BadgeTone.Warning -> SafeBankColors.statusWarningBg to SafeBankColors.statusWarningFg
        BadgeTone.Authorized -> SafeBankColors.statusAuthorizedBg to SafeBankColors.statusAuthorizedFg
        BadgeTone.Success -> SafeBankColors.statusSuccessBg to SafeBankColors.statusSuccessFg
        BadgeTone.Danger -> SafeBankColors.statusDangerBg to SafeBankColors.statusDangerFg
        BadgeTone.Neutral -> SafeBankColors.statusNeutralBg to SafeBankColors.statusNeutralFg
    }
    Surface(
        modifier = modifier,
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(4.dp),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 提示条色调。 */
enum class BannerTone {
    /** 信息 / 下一步提示（蓝灰竖条） */
    Info,

    /** 成功 */
    Success,

    /** 警告（如"仅撤销银行侧授权"） */
    Warning,

    /** 错误（行内错误条，可带「重试」操作） */
    Error,
}

/**
 * 提示条（InfoBanner）：左侧 3dp 竖条 + 正文，可选右侧操作按钮（「重试」等）。
 *
 * - [BannerTone.Info]：对应 Web 的 `.action-next-step`（左竖条语义，§3.6）；
 * - [BannerTone.Error]：对应 `.risk-error`（行内错误，§1.3）。
 */
@Composable
fun InfoBanner(
    text: String,
    tone: BannerTone = BannerTone.Info,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val (background, foreground) = when (tone) {
        BannerTone.Info -> SafeBankColors.nextStepBg to SafeBankColors.nextStepFg
        BannerTone.Success -> SafeBankColors.riskGreenBg to SafeBankColors.riskGreenFg
        BannerTone.Warning -> SafeBankColors.riskYellowBg to SafeBankColors.riskYellowFg
        BannerTone.Error -> SafeBankColors.inlineErrorBg to SafeBankColors.inlineErrorFg
    }
    val border: BorderStroke? = when (tone) {
        BannerTone.Info -> BorderStroke(1.dp, SafeBankColors.nextStepBorder)
        BannerTone.Error -> BorderStroke(1.dp, SafeBankColors.riskRedBorder)
        BannerTone.Success, BannerTone.Warning -> null
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.small,
        border = border,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(foreground),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Spacer(modifier = Modifier.width(8.dp))
                action()
            }
        }
    }
}

/** 取业务色的快捷方法（例如风险分层卡、列表行标记需要按语义拿色时用）。 */
fun riskToneColors(level: String?): Pair<Color, Color> = when (level?.uppercase()) {
    "RED" -> SafeBankColors.riskRedBg to SafeBankColors.riskRedFg
    "YELLOW" -> SafeBankColors.riskYellowBg to SafeBankColors.riskYellowFg
    "GREEN" -> SafeBankColors.riskGreenBg to SafeBankColors.riskGreenFg
    else -> SafeBankColors.nextStepBg to SafeBankColors.nextStepFg
}
