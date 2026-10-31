package cn.safebank.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * SafeBank 主题（docs/ui-design-spec.md §6.1）。
 *
 * 设计决策：
 * - **强制浅色**：银行演示只有浅色方案，不跟随系统深色模式（§7.6）；
 * - **关闭动态取色**：Android 12+ 动态取色会把品牌蓝换成壁纸色（§6.1）；
 * - `surfaceTint` 设为白色、全站 elevation 为 0：M3 的 tonal 抬升会给表面染色，
 *   而设计稿层次完全靠"1dp 描边 + 白/#F5F7FA/#F3F6FF 底色差"（§1.7）。
 *
 * 保留 `SafeBankAppTheme` 名称与可零参数调用的签名（导航外壳已引用）。
 * `darkTheme` / `dynamicColor` 参数仅为兼容调用方保留，实际被忽略。
 */
private val SafeBankLightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = SelectedBackground,
    onPrimaryContainer = HeadingBlue,
    inversePrimary = SelectedBackground,

    secondary = HeadingBlue,
    onSecondary = Color.White,
    secondaryContainer = SelectedBackground,
    onSecondaryContainer = HeadingBlue,

    tertiary = BrandRed,
    onTertiary = Color.White,
    tertiaryContainer = SafeBankColors.riskRedBg,
    onTertiaryContainer = SafeBankColors.riskRedFg,

    background = PageBackground,
    onBackground = TextPrimary,

    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceBubble,
    onSurfaceVariant = TextSecondary,
    surfaceTint = SurfaceWhite,
    surfaceContainerLowest = SurfaceWhite,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceContainerSubtle,
    surfaceContainerHigh = SurfaceResultTotal,
    surfaceContainerHighest = SurfaceTrack,

    outline = LineColor,
    outlineVariant = DividerSoft,

    error = Color(0xFF9C3030),
    onError = Color.White,
    errorContainer = Color(0xFFFFF0F2),
    onErrorContainer = Color(0xFFA22237),

    inverseSurface = HeadingBlue,
    inverseOnSurface = Color.White,
    scrim = Color(0xFF0F172A),
)

@Suppress("UNUSED_PARAMETER")
@Composable
fun SafeBankAppTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SafeBankLightColorScheme,
        typography = SafeBankTypography,
        shapes = SafeBankShapes,
        content = content,
    )
}
