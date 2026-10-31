package cn.safebank.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 给所有**非 GET** 请求自动加 `X-CSRF-Token`（docs/api-contract.md §2.2）。
 *
 * - token 来自 `GET /api/session` 响应体的 `csrf` 字段，由 [tokenProvider] 实时读取
 *   （会话重建后自动换成新 token，无需改请求对象）；
 * - GET 不需要 CSRF（后端判据是"方法不是 GET"）；
 * - 不发 `Origin` 头（OkHttp 默认不发；发了反而可能 403 ORIGIN_DENIED）。
 */
class CsrfInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method.equals("GET", ignoreCase = true)) {
            return chain.proceed(request)
        }
        val token = tokenProvider().orEmpty()
        if (token.isBlank() || request.header(HEADER) != null) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header(HEADER, token).build())
    }

    companion object {
        const val HEADER = "X-CSRF-Token"
    }
}
