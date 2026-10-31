package cn.safebank.app.data.network

import cn.safebank.app.data.model.SessionVo
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * SafeBank HTTP 客户端（数据层内部使用，页面不要直接依赖）。
 *
 * 职责：
 * 1. **会话**：首次请求前自动 `GET /api/session`，保存 `JSESSIONID`（[MemoryCookieJar]）
 *    与 `csrf`（[AtomicReference]）；`ensureSession(force = true)` 可强制重建。
 * 2. **CSRF**：所有非 GET 请求由 [CsrfInterceptor] 自动加 `X-CSRF-Token`。
 * 3. **会话失效自愈**：任何请求收到 `401 SESSION_REQUIRED` / `403 CSRF_REQUIRED` 时，
 *    清空 cookie + csrf → 重新 `GET /api/session` → **重放一次**原请求
 *    （这两个错误都由 SessionGuard 在业务逻辑之前拦下，重放不会重复扣款）。
 * 4. **错误统一**：非 2xx 解析为 [ApiException]；网络层异常也包装为 [ApiException]。
 * 5. **baseUrl 不缓存**：[baseUrlProvider] 每次请求都读当前值（设置页可随时改）。
 *
 * 并发：会话建立用 [Mutex] 串行化；cookie 访问由 [MemoryCookieJar] 加锁。
 */
class SafeBankHttp(
    private val baseUrlProvider: () -> String,
) {

    private val csrfToken = AtomicReference<String?>(null)
    private val cookieJar = MemoryCookieJar()
    private val sessionMutex = Mutex()

    @Volatile
    private var cachedSession: SessionVo? = null

    /** 普通接口。 */
    private val client: OkHttpClient = buildClient(READ_TIMEOUT_SECONDS)

    /** `/api/chat` 可能要等模型 + 授权，读超时放宽到 130 秒（契约 §8.6）。 */
    private val chatClient: OkHttpClient = buildClient(CHAT_READ_TIMEOUT_SECONDS)

    /** 当前缓存会话（未建立时为 null），供设置页展示。 */
    fun currentSession(): SessionVo? = cachedSession

    /**
     * 建立会话；[force] = true 时先清空 cookie/csrf 再重新申请（设置页「连通性自检」用）。
     */
    suspend fun ensureSession(force: Boolean = false): SessionVo = sessionMutex.withLock {
        val cached = cachedSession
        if (!force && cached != null && !csrfToken.get().isNullOrBlank()) {
            return@withLock cached
        }
        resetSessionState()
        val fresh = fetchSession()
        cachedSession = fresh
        csrfToken.set(fresh.csrf)
        fresh
    }

    // ------------------------------------------------------------------ 请求 API

    internal suspend fun <T> get(path: String, type: Type): T =
        decode(requestWithSession("GET", path, null), type)

    internal suspend fun <T> post(path: String, body: Any?, type: Type): T =
        decode(requestWithSession("POST", path, body), type)

    /** 只关心成功/失败、不解析响应体的 POST（模拟类端点）。 */
    internal suspend fun postUnit(path: String, body: Any? = null) {
        requestWithSession("POST", path, body)
    }

    // ------------------------------------------------------------------ 内部实现

    private suspend fun requestWithSession(method: String, path: String, body: Any?): String? {
        if (path == SESSION_PATH) {
            return networkCall(method, path, body)
        }
        ensureSession()
        return try {
            networkCall(method, path, body)
        } catch (expired: SessionExpiredException) {
            // 会话失效：重建一次后重放原请求（仅一次，绝不循环）。
            ensureSession(force = true)
            try {
                networkCall(method, path, body)
            } catch (retryExpired: SessionExpiredException) {
                throw retryExpired.apiException
            }
        } catch (network: ApiException) {
            // 空闲后连接池里的 keep-alive 连接可能已被服务端关闭（unexpected end of stream）。
            // 只对只读 GET 丢弃旧连接后重试一次；authorize/submit 等写操作绝不自动重试。
            if (method == "GET" && network.isNetworkError) {
                evictConnections()
                networkCall(method, path, body)
            } else {
                throw network
            }
        }
    }

    /** 丢弃两个客户端连接池中的空闲连接，下次请求会新建 TCP 连接。 */
    private fun evictConnections() {
        client.connectionPool.evictAll()
        chatClient.connectionPool.evictAll()
    }

    private suspend fun fetchSession(): SessionVo {
        val raw = networkCall("GET", SESSION_PATH, null)
        if (raw.isNullOrBlank()) {
            throw ApiException(ApiException.EMPTY_RESPONSE, "服务器未返回会话信息，请检查后端地址", 200)
        }
        val session: SessionVo? = try {
            SafeBankJson.decode(raw, SessionVo::class.java)
        } catch (e: Exception) {
            throw ErrorParser.unexpected(e)
        }
        if (session == null) {
            throw ApiException(ApiException.EMPTY_RESPONSE, "服务器未返回会话信息，请检查后端地址", 200)
        }
        if (session.csrf.isBlank()) {
            throw ApiException(ApiException.PARSE_ERROR, "会话响应缺少 csrf 字段", 200, raw)
        }
        return session
    }

    private suspend fun networkCall(method: String, path: String, body: Any?): String? =
        withContext(Dispatchers.IO) {
            val request = buildRequest(method, path, body)
            val response: Response = try {
                (if (path == CHAT_PATH) chatClient else client).newCall(request).execute()
            } catch (e: IOException) {
                throw ErrorParser.network(e)
            }
            response.use { resp ->
                val text = resp.body?.string()
                if (resp.isSuccessful) {
                    text
                } else {
                    val error = ErrorParser.parse(resp.code, text)
                    if (path != SESSION_PATH && ErrorParser.isSessionExpiry(error)) {
                        throw SessionExpiredException(error)
                    }
                    throw error
                }
            }
        }

    private fun buildRequest(method: String, path: String, body: Any?): Request {
        val baseUrl = baseUrlProvider().trim()
        if (baseUrl.isEmpty()) {
            throw ApiException(ApiException.SERVER_NOT_CONFIGURED, "请先在「设置」中填写服务器地址", 0)
        }
        val url = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw ApiException(
                ApiException.SERVER_NOT_CONFIGURED,
                "服务器地址不合法：$baseUrl",
                0,
            )
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        if (method == "GET") {
            return builder.get().build()
        }
        val json = if (body == null) "{}" else SafeBankJson.gson.toJson(body)
        return builder.method(method, json.toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun <T> decode(raw: String?, type: Type): T {
        if (raw.isNullOrBlank()) {
            throw ApiException(ApiException.EMPTY_RESPONSE, "服务器返回了空响应", 200)
        }
        return try {
            SafeBankJson.decode(raw, type)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ErrorParser.unexpected(e)
        }
    }

    private fun buildClient(readTimeoutSeconds: Long): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(CsrfInterceptor { csrfToken.get() })
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 契约要求：不要对 submit/authorize 这类请求做隐式自动重试（页面自己决定查单）。
            .retryOnConnectionFailure(false)
            // 后端不会重定向；不跟随可避免把 POST 变成对另一个 host 的 GET。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    private fun resetSessionState() {
        cookieJar.clear()
        csrfToken.set(null)
        cachedSession = null
    }

    /** 内部信号：会话失效，需要重建后重放（不暴露给页面）。 */
    private class SessionExpiredException(
        val apiException: ApiException,
    ) : IOException(apiException.message)

    companion object {
        const val SESSION_PATH = "/api/session"
        const val CHAT_PATH = "/api/chat"

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val CHAT_READ_TIMEOUT_SECONDS = 130L
        private const val WRITE_TIMEOUT_SECONDS = 20L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
