package cn.safebank.app.data

import cn.safebank.app.data.network.ApiException
import cn.safebank.app.data.network.ErrorParser
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误体解析测试（纯 JVM，不联网）：
 * 覆盖 docs/api-contract.md §3.2 的 ErrorVO / RiskErrorVO / Spring 默认错误体三种形态。
 */
class ErrorParserTest {

    @Test
    fun `error vo keeps code message http status and body`() {
        val body = """{"code":"VALIDATION_ERROR","message":"请输入收款人"}"""
        val error = ErrorParser.parse(400, body)

        assertEquals("VALIDATION_ERROR", error.code)
        assertEquals("请输入收款人", error.message)
        assertEquals(400, error.httpStatus)
        assertEquals(body, error.body)
        assertFalse(error.isNetworkError)
        assertNull(error.actionId)
        assertNull(error.security)
        assertNull(error.retryAt)
        // 页面可以统一按 IO 异常处理
        assertTrue(java.io.IOException::class.java.isAssignableFrom(error.javaClass))
    }

    @Test
    fun `risk error vo exposes action id security and retry at`() {
        val body = """
        {
          "code": "STEP_UP_REQUIRED",
          "message": "该操作需要额外的验证",
          "actionId": "9a1f3c62-5b1e-4a77-9c2d-1f0b7a5e4d11",
          "security": {
            "decisionId": "e77c0a1b",
            "policyVersion": "bank-risk-v1",
            "riskLevel": "RED",
            "requiredAssurance": "STEP_UP",
            "reasonCode": "DAILY_OUTGOING_THRESHOLD_EXCEEDED",
            "businessDate": "2026-10-27",
            "effectiveMinor": 100000,
            "projectedMinor": 250000,
            "validUntil": "2026-10-27T14:35:12Z",
            "nextStep": "VERIFY_THEN_CONFIRM",
            "verificationMode": "LOCAL_DEMO_ONLY",
            "needsRefresh": false
          },
          "retryAt": "2026-10-27T14:40:00Z"
        }
        """.trimIndent()
        val error = ErrorParser.parse(403, body)

        assertEquals("STEP_UP_REQUIRED", error.code)
        assertEquals(403, error.httpStatus)
        assertEquals("9a1f3c62-5b1e-4a77-9c2d-1f0b7a5e4d11", error.actionId)
        assertEquals("2026-10-27T14:40:00Z", error.retryAt)
        val security = error.security
        assertNotNull(security)
        assertTrue(security!!.isRed)
        assertEquals(250000L, security.projectedMinor)
    }

    @Test
    fun `session required and csrf required are recognized as session expiry`() {
        val session = ErrorParser.parse(401, """{"code":"SESSION_REQUIRED","message":"请刷新页面建立本地演示会话"}""")
        assertTrue(ErrorParser.isSessionExpiry(session))
        assertTrue(session.isSessionError)

        val csrf = ErrorParser.parse(403, """{"code":"CSRF_REQUIRED","message":"请刷新页面建立本地演示会话"}""")
        assertTrue(ErrorParser.isSessionExpiry(csrf))

        // Origin 被拒不是会话问题，不能触发重建会话
        val origin = ErrorParser.parse(403, """{"code":"ORIGIN_DENIED","message":"请刷新页面建立本地演示会话"}""")
        assertFalse(ErrorParser.isSessionExpiry(origin))
    }

    @Test
    fun `spring default error body becomes readable message`() {
        val body = """
        {"timestamp":"2026-10-27T14:20:11.123+00:00","status":400,
         "error":"Bad Request","path":"/api/recipients"}
        """.trimIndent()
        val error = ErrorParser.parse(400, body)

        assertEquals(400, error.httpStatus)
        assertEquals("HTTP_400", error.code)
        assertTrue(error.message.contains("请求不合法"))
        assertTrue(error.message.contains("Bad Request"))
        assertTrue(error.message.contains("/api/recipients"))
        assertEquals(body, error.body)
    }

    @Test
    fun `spring default error body without error field still readable`() {
        val error = ErrorParser.parse(
            500,
            """{"timestamp":"2026-10-27T14:20:11Z","status":500,"path":"/api/chat"}""",
        )
        assertEquals("HTTP_500", error.code)
        assertTrue(error.message.contains("服务器内部错误"))
        assertTrue(error.message.contains("/api/chat"))
    }

    @Test
    fun `empty and non json bodies fall back to status message`() {
        val empty = ErrorParser.parse(503, null)
        assertEquals("HTTP_503", empty.code)
        assertEquals(503, empty.httpStatus)
        assertTrue(empty.message.contains("暂不可用"))

        val html = ErrorParser.parse(502, "<html><body>Bad Gateway</body></html>")
        assertEquals("HTTP_502", html.code)
        assertTrue(html.message.contains("网关"))

        val emptyString = ErrorParser.parse(401, "")
        assertEquals("HTTP_401", emptyString.code)
        assertTrue(emptyString.message.contains("会话已失效"))
    }

    @Test
    fun `business error without message uses chinese fallback`() {
        val error = ErrorParser.parse(400, """{"code":"VERIFICATION_UNAVAILABLE"}""")
        assertEquals("VERIFICATION_UNAVAILABLE", error.code)
        assertTrue(error.message.contains("risk-demo"))

        val locked = ErrorParser.parse(423, """{"code":"SECURITY_LOCKED","message":""}""")
        assertTrue(locked.message.contains("锁定"))
    }

    @Test
    fun `network exceptions are wrapped as network errors`() {
        val unknownHost = ErrorParser.network(UnknownHostException("10.0.2.2"))
        assertEquals(ApiException.NETWORK_ERROR, unknownHost.code)
        assertEquals(0, unknownHost.httpStatus)
        assertTrue(unknownHost.isNetworkError)
        assertTrue(unknownHost.message.contains("服务器地址"))

        val refused = ErrorParser.network(ConnectException("Connection refused"))
        assertTrue(refused.message.contains("后端已启动"))

        val timeout = ErrorParser.network(SocketTimeoutException("timeout"))
        assertTrue(timeout.message.contains("超时"))
    }

    @Test
    fun `unexpected parse failure is not a network error`() {
        val parseError = ErrorParser.unexpected(IllegalStateException("Expected BEGIN_OBJECT"))
        assertEquals(ApiException.PARSE_ERROR, parseError.code)
        assertEquals(-1, parseError.httpStatus)
        assertFalse(parseError.isNetworkError)
        assertTrue(parseError.message.contains("响应格式异常"))
    }
}
