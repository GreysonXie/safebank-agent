package cn.safebank.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SafeBank 设计令牌（来源：docs/ui-design-spec.md §1、§6.1）。
 *
 * 只建"蓝系"品牌令牌：Web 端早期的 `--green` 已被 theme.css 重新指向主色蓝，
 * 因此 Android 端**不要**创建绿色主色令牌（见 ui-spec §8 第 1 条）。
 * 页面/组件层不要直接写十六进制，统一使用 [SafeBankColors] 或 MaterialTheme.colorScheme。
 */

// ---------------------------------------------------------------- 核心语义色（§1.2）

/** `--primary` 主色 / 品牌蓝：#3A5ECF（白字对比度 5.70） */
val BrandBlue = Color(0xFF3A5ECF)

/** `--primary-hover` 主按钮按下深色：#2948AE */
val BrandBluePressed = Color(0xFF2948AE)

/** `--brand-red` 品牌红：#D80C18，仅装饰（logo 斜杠、激活指示条），不作正文色 */
val BrandRed = Color(0xFFD80C18)

/** `--heading-blue` 深蓝：#1A4F99，首张账户卡底、次级按钮文字、默认状态徽章 */
val HeadingBlue = Color(0xFF1A4F99)

/** `--text` 一级正文：#0F172A */
val TextPrimary = Color(0xFF0F172A)

/** `--secondary-text` 二级文字：#526173（白底 6.33 ✅） */
val TextSecondary = Color(0xFF526173)

/** `--page-background` 页面背景：#F5F7FA */
val PageBackground = Color(0xFFF5F7FA)

/** `--surface` 表面（面板/卡片/输入框）：#FFFFFF */
val SurfaceWhite = Color(0xFFFFFFFF)

/** `--selected-background` 选中/浅蓝底：#EDF2FF */
val SelectedBackground = Color(0xFFEDF2FF)

/** `--line` 边框/分隔线：#E2E8F0（仅装饰） */
val LineColor = Color(0xFFE2E8F0)

// ---------------------------------------------------------------- 辅助底色（§1.3 / §6.1）

/** 页脚/次级区：#F8FAFC */
val SurfaceLow = Color(0xFFF8FAFC)

/** 账户卡、收支汇总卡：#F3F6FF */
val SurfaceContainerSubtle = Color(0xFFF3F6FF)

/** 余额结果条：#F1F6F9 */
val SurfaceResultTotal = Color(0xFFF1F6F9)

/** 分类进度轨：#EDF1F9 */
val SurfaceTrack = Color(0xFFEDF1F9)

/** 助手气泡、模式徽章、原始数据块：#F1F5F9 */
val SurfaceBubble = Color(0xFFF1F5F9)

/** 分隔线、交易行底边：#F1F5F9 */
val DividerSoft = Color(0xFFF1F5F9)

/** 上下文记忆区描边：#DCE3EF */
val ContextBorder = Color(0xFFDCE3EF)

// ---------------------------------------------------------------- 业务扩展色（§1.4 / §6.1）

/**
 * 不进 [androidx.compose.material3.ColorScheme] 的业务色。
 * 各页面从这里取状态/风险/提示色；组件内部也只用这里，不写死十六进制。
 */
object SafeBankColors {
    // 品牌
    val brandBlue: Color = BrandBlue
    val brandBluePressed: Color = BrandBluePressed
    val brandRed: Color = BrandRed
    val headingBlue: Color = HeadingBlue

    // 文本 / 底色
    val textPrimary: Color = TextPrimary
    val textSecondary: Color = TextSecondary
    val pageBackground: Color = PageBackground
    val surface: Color = SurfaceWhite
    val selectedBackground: Color = SelectedBackground
    val line: Color = LineColor
    val divider: Color = DividerSoft
    val contextBorder: Color = ContextBorder

    // 状态徽章（§1.4：6 套底色 + 2 个回落默认蓝）
    /** 默认状态徽章（CANCEL_REQUESTED / PARTIAL_SUCCESS / 未知） */
    val statusDefaultBg: Color = Color(0xFFEDF2FF)
    val statusDefaultFg: Color = Color(0xFF1A4F99)

    /** AWAITING_AUTH / PENDING / UNKNOWN */
    val statusWarningBg: Color = Color(0xFFFFF3D6)
    val statusWarningFg: Color = Color(0xFF785000)

    /** AUTHORIZED */
    val statusAuthorizedBg: Color = Color(0xFFE9EDFF)
    val statusAuthorizedFg: Color = Color(0xFF354B9A)

    /** VERIFIED_SUCCESS */
    val statusSuccessBg: Color = Color(0xFFE5F5EE)
    val statusSuccessFg: Color = Color(0xFF176345)

    /** VERIFIED_FAILED */
    val statusDangerBg: Color = Color(0xFFFFE8E8)
    val statusDangerFg: Color = Color(0xFF9C3030)

    /** CANCELLED */
    val statusNeutralBg: Color = Color(0xFFEEF1F5)
    val statusNeutralFg: Color = Color(0xFF475569)

    // 风险分层卡（§3.6 / §6.1 扩展色：GREEN/YELLOW/RED 四档补齐）
    val riskGreenBg: Color = Color(0xFFE5F5EE)
    val riskGreenFg: Color = Color(0xFF176345)
    val riskYellowBg: Color = Color(0xFFFFF3D6)
    val riskYellowFg: Color = Color(0xFF785000)
    val riskRedBg: Color = Color(0xFFFFF5F5)
    val riskRedFg: Color = Color(0xFF8E2635)
    val riskRedBorder: Color = Color(0xFFE9B7BD)

    // 下一步提示 / 行内错误 / 调试面板
    val nextStepBg: Color = Color(0xFFF5F8FD)
    val nextStepBorder: Color = Color(0xFFB5C7E8)
    val nextStepFg: Color = Color(0xFF334155)
    val inlineErrorBg: Color = Color(0xFFFFF0F2)
    val inlineErrorFg: Color = Color(0xFFA22237)

    /** Web 旧版账单结果错误色（§8 第 13 条：与蓝主题不协调，新代码用 [inlineErrorFg]） */
    val legacyResultError: Color = Color(0xFF9A4B32)

    /** 故障实验区（仅 debug） */
    val debugPanel: Color = Color(0xFFFFF7E7)

    // 收支 / 金额（Android 增补：Web 收支双卡同底色，见 §8 第 2 条）
    /** 收入：绿底绿字（对比度 6.41 ✅） */
    val incomeBg: Color = Color(0xFFE5F5EE)
    val incomeFg: Color = Color(0xFF176345)

    /** 支出：红底红字（对比度 7.92 ✅） */
    val expenseBg: Color = Color(0xFFFFF5F5)
    val expenseFg: Color = Color(0xFF8E2635)
}
