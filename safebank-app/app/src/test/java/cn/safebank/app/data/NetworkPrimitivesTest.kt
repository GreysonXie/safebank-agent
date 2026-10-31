package cn.safebank.app.data

import cn.safebank.app.data.network.CsrfInterceptor
import cn.safebank.app.data.network.MemoryCookieJar
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络层纯逻辑测试：内存 CookieJar 与 CSRF 拦截器（不发起真实网络请求）。
 */
class NetworkPrimitivesTest {

    // ------------------------------------------------------------ MemoryCookieJar

    @Test
    fun `cookie jar saves jsessionid per host and loads it`() {
        val jar = MemoryCookieJar()
        val sessionUrl = "http://10.0.2.2:8080/api/session".toHttpUrl()
        val cookie = Cookie.parse(sessionUrl, "JSESSIONID=abc123; Path=/; HttpOnly")
        assertNotNull(cookie)
        jar.saveFromResponse(sessionUrl, listOf(cookie!!))
        assertEquals(1, jar.size())

        val loaded = jar.loadForRequest("http://10.0.2.2:8080/api/accounts".toHttpUrl())
        assertEquals(1, loaded.size)
        assertEquals("JSESSIONID", loaded[0].name)
        assertEquals("abc123", loaded[0].value)
    }

    @Test
    fun `cookie jar is per host and clearable`() {
        val jar = MemoryCookieJar()
        val url = "http://10.0.2.2:8080/api/session".toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "JSESSIONID=abc123; Path=/")!!))

        // 换一个服务器地址（设置页可改）不应串 cookie
        assertTrue(jar.loadForRequest("http://127.0.0.1:8080/api/accounts".toHttpUrl()).isEmpty())

        // 会话重建时要能彻底清空
        jar.clear()
        assertEquals(0, jar.size())
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun `cookie jar replaces same cookie instead of duplicating`() {
        val jar = MemoryCookieJar()
        val url = "http://10.0.2.2:8080/api/session".toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "JSESSIONID=abc123; Path=/")!!))
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "JSESSIONID=new456; Path=/")!!))

        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("new456", loaded[0].value)
    }

    // ------------------------------------------------------------ CsrfInterceptor

    @Test
    fun `csrf interceptor adds header to non get requests`() {
        val chain = CapturingChain(postRequest())
        CsrfInterceptor { "csrf-token-1" }.intercept(chain)

        val sent = chain.proceeded
        assertNotNull(sent)
        assertEquals("csrf-token-1", sent!!.header(CsrfInterceptor.HEADER))
    }

    @Test
    fun `csrf interceptor leaves get requests untouched`() {
        val chain = CapturingChain(getRequest())
        CsrfInterceptor { "csrf-token-1" }.intercept(chain)

        assertNull(chain.proceeded!!.header(CsrfInterceptor.HEADER))
    }

    @Test
    fun `csrf interceptor skips blank token and keeps explicit header`() {
        val noTokenChain = CapturingChain(postRequest())
        CsrfInterceptor { null }.intercept(noTokenChain)
        assertNull(noTokenChain.proceeded!!.header(CsrfInterceptor.HEADER))

        val blankTokenChain = CapturingChain(postRequest())
        CsrfInterceptor { "" }.intercept(blankTokenChain)
        assertNull(blankTokenChain.proceeded!!.header(CsrfInterceptor.HEADER))

        val existing = Request.Builder()
            .url(API_URL)
            .header(CsrfInterceptor.HEADER, "explicit-token")
            .post("{}".toRequestBody(JSON))
            .build()
        val existingChain = CapturingChain(existing)
        CsrfInterceptor { "csrf-token-1" }.intercept(existingChain)
        assertEquals("explicit-token", existingChain.proceeded!!.header(CsrfInterceptor.HEADER))
    }

    // ------------------------------------------------------------ 辅助

    private fun getRequest(): Request = Request.Builder().url(API_URL).get().build()

    private fun postRequest(): Request = Request.Builder()
        .url(API_URL)
        .post("{}".toRequestBody(JSON))
        .build()

    /** 只记录 `proceed` 收到的请求，不真正走网络。 */
    private class CapturingChain(private val original: Request) : Interceptor.Chain {
        var proceeded: Request? = null

        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException("not used in unit test")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private companion object {
        const val API_URL = "http://127.0.0.1:8080/api/actions/act-1/authorize"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
