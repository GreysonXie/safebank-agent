package cn.safebank.app.data

import cn.safebank.app.data.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Formatters] 金额解析/格式化边界测试（纯 JVM，不联网）。
 */
class FormattersTest {

    // ------------------------------------------------------------ parseAmountToMinor

    @Test
    fun `parseAmountToMinor converts yuan to minor units`() {
        assertEquals(50000L, Formatters.parseAmountToMinor("500"))
        assertEquals(50050L, Formatters.parseAmountToMinor("500.5"))
        assertEquals(50050L, Formatters.parseAmountToMinor("500.50"))
        assertEquals(123456L, Formatters.parseAmountToMinor("1,234.56"))
        assertEquals(1L, Formatters.parseAmountToMinor("0.01"))
        assertEquals(0L, Formatters.parseAmountToMinor("0"))
        assertEquals(50L, Formatters.parseAmountToMinor(".5"))
        assertEquals(8880L, Formatters.parseAmountToMinor("  88.8 "))
    }

    @Test
    fun `parseAmountToMinor strips currency symbols`() {
        assertEquals(123456L, Formatters.parseAmountToMinor("¥1,234.56"))
        assertEquals(1L, Formatters.parseAmountToMinor("￥0.01"))
    }

    @Test
    fun `parseAmountToMinor supports negative and rejects bad input`() {
        assertEquals(-1250L, Formatters.parseAmountToMinor("-12.5"))
        // 空/空白/非法
        assertNull(Formatters.parseAmountToMinor(""))
        assertNull(Formatters.parseAmountToMinor("   "))
        assertNull(Formatters.parseAmountToMinor("abc"))
        assertNull(Formatters.parseAmountToMinor("12元"))
        assertNull(Formatters.parseAmountToMinor("1,234.5.6"))
        // 超过两位小数直接拒绝（不能静默四舍五入）
        assertNull(Formatters.parseAmountToMinor("1.234"))
        assertNull(Formatters.parseAmountToMinor("0.001"))
        // 超出 Long 分范围 → null（Long.MAX_VALUE ≈ 92,233,720,368,547,758 分）
        assertNull(Formatters.parseAmountToMinor("92233720368547758.08"))
    }

    // ------------------------------------------------------------ money

    @Test
    fun `money formats minor units with two decimals`() {
        assertEquals("¥0.00", Formatters.money(0))
        assertEquals("¥0.01", Formatters.money(1))
        assertEquals("¥0.99", Formatters.money(99))
        assertEquals("¥1.00", Formatters.money(100))
        assertEquals("¥1,234.00", Formatters.money(123400))
        assertEquals("¥1,234,567.89", Formatters.money(123456789))
        assertEquals("¥-0.01", Formatters.money(-1))
        assertEquals("¥-1,234,567.89", Formatters.money(-123456789))
    }

    @Test
    fun `money handles Long extremes without overflow`() {
        // BigDecimal 缩放，不能用 minor * 100 / 100 之类的整数运算
        assertEquals("¥92,233,720,368,547,758.07", Formatters.money(Long.MAX_VALUE))
        assertEquals("¥-92,233,720,368,547,758.08", Formatters.money(Long.MIN_VALUE))
    }

    @Test
    fun `moneySigned and moneyCompact`() {
        assertEquals("¥0.00", Formatters.moneySigned(0))
        assertEquals("+¥1,200.00", Formatters.moneySigned(120000))
        assertEquals("-¥1,200.00", Formatters.moneySigned(-120000))

        assertEquals("0", Formatters.moneyCompact(0))
        assertEquals("15", Formatters.moneyCompact(1500))
        assertEquals("1,234", Formatters.moneyCompact(123400))
    }

    // ------------------------------------------------------------ 状态文案（页面直接复用）

    @Test
    fun `status labels cover contract enums`() {
        assertEquals("等待确认", Formatters.actionStatusLabel("AWAITING_AUTH"))
        assertEquals("银行已确认成功", Formatters.actionStatusLabel("VERIFIED_SUCCESS"))
        assertEquals("结果未知，需查单", Formatters.actionStatusLabel("UNKNOWN"))
        assertEquals("部分完成", Formatters.workflowStateLabel("PARTIAL_SUCCESS"))
        assertEquals("已申请取消", Formatters.workflowStateLabel("CANCEL_REQUESTED"))
        assertEquals("等待前置步骤", Formatters.planNodeStateLabel("WAITING_DEPENDENCY"))
        assertEquals("撤销代扣", Formatters.actionKindLabel("REVOKE"))
        assertEquals("每季度", Formatters.billingIntervalLabel("QUARTERLY"))
        // 未知值不能崩
        assertEquals("WHAT", Formatters.actionStatusLabel("WHAT"))
        assertNull(Formatters.remainingSeconds(null))
    }

    @Test
    fun `date formatters degrade gracefully`() {
        assertEquals("—", Formatters.shortDate(null))
        assertEquals("09-20", Formatters.shortDate("2026-09-20"))
        assertEquals("bad-date", Formatters.shortDate("bad-date"))
        assertEquals("—", Formatters.shortDateTime(null))
    }
}
