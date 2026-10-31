package cn.safebank.app.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 纯内存 CookieJar（进程内单例，跟着 [SafeBankHttp] 走）。
 *
 * 按 docs/api-contract.md §2.4：
 * - 只保存后端下发的 `JSESSIONID`（HttpOnly / SameSite=Strict 对 OkHttp 无影响）；
 * - **不做磁盘持久化**：后端会话 30 分钟超时 + 内存态数据，跨进程复用没有意义，
 *   反而会拿到已失效的会话；
 * - 会话重建时调用 [clear] 清空。
 */
class MemoryCookieJar : CookieJar {

    private val lock = Any()
    private val store = LinkedHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            for (cookie in cookies) {
                store[key(cookie)] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val expired = store.values.filter { it.expiresAt <= now }
            if (expired.isNotEmpty()) {
                for (cookie in expired) store.remove(key(cookie))
            }
            return store.values.filter { it.matches(url) && it.expiresAt > now }
        }
    }

    /** 会话失效 / 重建时调用。 */
    fun clear() {
        synchronized(lock) { store.clear() }
    }

    /** 当前保存的 cookie 数量（调试/单测用）。 */
    fun size(): Int = synchronized(lock) { store.size }

    private fun key(cookie: Cookie): String = "${cookie.domain}|${cookie.path}|${cookie.name}"
}
