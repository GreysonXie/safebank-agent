package cn.safebank.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * SafeBank 圆角（docs/ui-design-spec.md §6.3）。
 *
 * 操作卡用 9dp、模式徽章用 5dp 这类非标准值由使用方局部 `RoundedCornerShape` 指定。
 */
val SafeBankShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // 状态徽章、进度条/轨、下一步提示
    small = RoundedCornerShape(8.dp),        // 按钮、输入框、下拉、chips、通知条、汇总卡
    medium = RoundedCornerShape(10.dp),      // 面板、PageHeader
    large = RoundedCornerShape(12.dp),       // 账户卡、上下文区、风险卡
    extraLarge = RoundedCornerShape(20.dp),  // 仅预留（大卡片/底部弹层）
)
