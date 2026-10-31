package cn.safebank.app

import android.content.Context
import cn.safebank.app.data.AndroidBankRepository
import cn.safebank.app.data.AppSettings
import cn.safebank.app.data.SafeBankRepository

/**
 * 轻量手写依赖容器（不引入 Hilt/Koin）：
 * 设置、网络客户端、仓库都在这里创建，供各页面共享。
 */
class AppContainer(context: Context) {

    val settings: AppSettings = AppSettings(context)

    val repository: SafeBankRepository by lazy { AndroidBankRepository(settings) }
}
