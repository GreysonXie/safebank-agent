package cn.safebank.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SafeBank 字体层级（docs/ui-design-spec.md §6.2）。
 *
 * - 不在 App 内下载字体，使用系统字体（Android 落 Noto Sans CJK）；
 * - 字号下限 11sp（Web 有 10px 的模式徽章/署名，手机不可读，见 §7.3）；
 * - 金额等宽数字由 `MoneyText` 追加 `fontFeatureSettings = "tnum"`。
 */
private fun safeBankTextStyle(
    sizeSp: Int,
    lineHeightSp: Int,
    weight: FontWeight,
    letterSpacingSp: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    letterSpacing = letterSpacingSp.sp,
)

val SafeBankTypography = Typography(
    // 页面 H1（宽屏 30sp / 手机 26sp 用 headlineMedium）
    displaySmall = safeBankTextStyle(30, 39, FontWeight.W700, -0.5),
    // 手机页面标题（ui-spec §2.4.3：26sp / 700）
    headlineMedium = safeBankTextStyle(26, 34, FontWeight.W700, -0.5),
    // 金额大号 / 账户卡余额
    headlineSmall = safeBankTextStyle(24, 31, FontWeight.W700, -0.4),
    // 面板 H2
    titleLarge = safeBankTextStyle(18, 24, FontWeight.W600),
    // 卡片 H3、导航项激活
    titleMedium = safeBankTextStyle(15, 22, FontWeight.W600),
    // 结果块 H3
    titleSmall = safeBankTextStyle(14, 20, FontWeight.W600),
    // 正文、气泡
    bodyLarge = safeBankTextStyle(14, 25, FontWeight.W400),
    // 交易行、分类行、空态、下一步提示
    bodyMedium = safeBankTextStyle(13, 23, FontWeight.W400),
    // 说明、字段标签、状态徽章
    bodySmall = safeBankTextStyle(12, 22, FontWeight.W400),
    // 按钮
    labelLarge = safeBankTextStyle(14, 20, FontWeight.W600),
    // chips
    labelMedium = safeBankTextStyle(12, 16, FontWeight.W500),
    // 署名、模式徽章、输入提示（下限 11sp，禁止 10sp）
    labelSmall = safeBankTextStyle(11, 16, FontWeight.W500, 0.2),
)
