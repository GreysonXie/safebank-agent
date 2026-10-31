package cn.safebank.app.data

import android.content.Context
import cn.safebank.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地设置（SharedPreferences）：
 * 服务器地址可改，便于模拟器（10.0.2.2）与真机（局域网 IP）切换。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("safebank_settings", Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(normalize(prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.DEFAULT_BASE_URL))
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    fun setBaseUrl(raw: String): Boolean {
        val value = normalize(raw)
        if (value.isEmpty()) return false
        prefs.edit().putString(KEY_BASE_URL, value).apply()
        _baseUrl.value = value
        return true
    }

    fun resetBaseUrl() {
        setBaseUrl(BuildConfig.DEFAULT_BASE_URL)
    }

    private fun normalize(raw: String): String {
        var v = raw.trim()
        if (v.isEmpty()) return ""
        if (!v.startsWith("http://", ignoreCase = true) && !v.startsWith("https://", ignoreCase = true)) {
            v = "http://$v"
        }
        while (v.endsWith("/")) v = v.dropLast(1)
        return v
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
    }
}
