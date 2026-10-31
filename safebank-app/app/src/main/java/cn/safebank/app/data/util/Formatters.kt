package cn.safebank.app.data.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 金额 / 日期 / 状态文案格式化。
 *
 * 后端金额一律整数分（Long）；UI 统一展示 `¥1,234.00`。
 */
object Formatters {

    private val GROUPED = java.text.DecimalFormat("#,##0.00")
    private val GROUPED_NO_DECIMAL = java.text.DecimalFormat("#,##0")

    /** 整数分 → "¥1,234.00" */
    fun money(minor: Long): String = "¥" + GROUPED.format(BigDecimal.valueOf(minor, 2))

    /** 整数分 → "¥1,234.00"，带正负号（收入 +，支出 -） */
    fun moneySigned(minor: Long): String = when {
        minor > 0 -> "+¥" + GROUPED.format(BigDecimal.valueOf(minor, 2))
        // 用 BigDecimal.abs() 避免 Long.MIN_VALUE 取负溢出
        minor < 0 -> "-¥" + GROUPED.format(BigDecimal.valueOf(minor, 2).abs())
        else -> money(0)
    }

    /** 整数分 → "1,234"（用于卡片上的大字金额，省略小数） */
    fun moneyCompact(minor: Long): String = GROUPED_NO_DECIMAL.format(BigDecimal.valueOf(minor, 2))

    /** "500.5"/"500.50"/"1,200" → 整数分；非法或超过两位小数返回 null。 */
    fun parseAmountToMinor(raw: String): Long? {
        val text = raw.trim().replace(",", "").replace("¥", "").replace("￥", "")
        if (text.isEmpty()) return null
        val decimal = text.toBigDecimalOrNull() ?: return null
        if (decimal.scale() > 2) return null
        return try {
            decimal.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** "2026-09-20" → "09-20"；无法解析时原样返回。 */
    fun shortDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            val date = LocalDate.parse(iso.take(10))
            date.format(DateTimeFormatter.ofPattern("MM-dd"))
        } catch (e: Exception) {
            iso
        }
    }

    /** ISO 时间戳 → "MM-dd HH:mm"（本地时区）。 */
    fun shortDateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            val instant = OffsetDateTime.parse(iso).toInstant()
            val local = instant.atZone(ZoneId.systemDefault())
            local.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        } catch (e: Exception) {
            iso
        }
    }

    /** 剩余秒数（用于 action 预览 5 分钟有效期展示）；无法解析返回 null。 */
    fun remainingSeconds(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val expiry = OffsetDateTime.parse(iso).toInstant()
            ChronoUnit.SECONDS.between(java.time.Instant.now(), expiry)
        } catch (e: Exception) {
            null
        }
    }

    /** 动作状态 → 中文标签。 */
    fun actionStatusLabel(status: String?): String = when (status) {
        "AWAITING_AUTH" -> "等待确认"
        "AUTHORIZED" -> "已授权，待提交"
        "VERIFIED_SUCCESS" -> "银行已确认成功"
        "VERIFIED_FAILED" -> "银行明确失败"
        "CANCELLED" -> "已取消"
        "PENDING" -> "银行受理中"
        "UNKNOWN" -> "结果未知，需查单"
        else -> status ?: "未知状态"
    }

    /** workflow 状态 → 中文标签。 */
    fun workflowStateLabel(state: String?): String = when (state) {
        "PLANNING" -> "规划中"
        "AWAITING_AUTH" -> "等待逐项确认"
        "PENDING" -> "银行处理中"
        "UNKNOWN" -> "结果未知"
        "VERIFIED_SUCCESS" -> "全部完成"
        "VERIFIED_FAILED" -> "失败"
        "PARTIAL_SUCCESS" -> "部分完成"
        "CANCELLED" -> "已取消"
        "CANCEL_REQUESTED" -> "已申请取消"
        else -> state ?: "未知"
    }

    /** 计划节点展示状态 → 中文标签。 */
    fun planNodeStateLabel(state: String?): String = when (state) {
        "VALIDATED" -> "已核验"
        "WAITING_DEPENDENCY" -> "等待前置步骤"
        "BLOCKED_UNKNOWN" -> "前置结果未知，已暂停"
        "AWAITING_AUTH" -> "等待确认"
        "AUTHORIZED" -> "已授权"
        "VERIFIED_SUCCESS" -> "已完成"
        "VERIFIED_FAILED" -> "失败"
        "CANCELLED" -> "已取消"
        "PENDING" -> "处理中"
        else -> state ?: "未知"
    }

    /** 动作种类 → 中文标签。 */
    fun actionKindLabel(kind: String?): String = when (kind) {
        "TRANSFER" -> "转账"
        "REVOKE" -> "撤销代扣"
        "ACTIVATE_MANDATE" -> "开启代扣"
        "SCHEDULE" -> "定时储蓄"
        "MANAGE_SCHEDULE" -> "管理定时计划"
        "QUERY_MANDATES" -> "查询代扣"
        else -> kind ?: "操作"
    }

    /** 卡片状态 → 中文标签。 */
    fun cardStatusLabel(status: String?): String = when (status) {
        "ACTIVE" -> "正常"
        "LOCKED" -> "已锁定"
        "LOST" -> "已挂失"
        else -> status ?: "未知状态"
    }

    /** 卡片类型 → 中文标签。 */
    fun cardTypeLabel(type: String?): String = when (type) {
        "DEBIT" -> "借记卡"
        "VIRTUAL" -> "虚拟卡"
        else -> type ?: "卡片"
    }

    /** 卡片操作状态（t020 只有 AWAITING_CONFIRMATION / COMPLETED / CANCELLED）。 */
    fun cardOperationStatusLabel(status: String?): String = when (status) {
        "AWAITING_CONFIRMATION" -> "待确认"
        "COMPLETED" -> "已完成"
        "CANCELLED" -> "已取消"
        else -> status ?: "未知状态"
    }

    /** 卡片操作类型 → 中文标签。 */
    fun cardOperationKindLabel(kind: String?): String = when (kind) {
        "APPLY" -> "申请新卡"
        "UPDATE_LIMITS" -> "调整限额"
        "UPDATE_CONTROLS" -> "调整支付开关"
        "LOCK" -> "锁定卡片"
        "UNLOCK" -> "解锁卡片"
        "REPORT_LOST" -> "挂失卡片"
        else -> kind ?: "卡片操作"
    }

    /** 定时计划状态 → 中文标签（t020 新增 PAUSED/CANCELLED）。 */
    fun scheduleStatusLabel(status: String?): String = when (status) {
        "ACTIVE" -> "生效中"
        "PAUSED" -> "已暂停"
        "CANCELLED" -> "已取消"
        else -> status ?: "未知状态"
    }

    /** 计费周期 → 中文。 */
    fun billingIntervalLabel(interval: String?): String = when (interval) {
        "MONTHLY" -> "每月"
        "QUARTERLY" -> "每季度"
        "YEARLY" -> "每年"
        else -> interval ?: "未知周期"
    }

    /** 当前月份 "YYYY-MM"（Asia/Shanghai）。 */
    fun currentMonth(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM"))

    /** 今天 "YYYY-MM-DD"。 */
    fun today(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
}
