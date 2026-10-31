package cn.safebank.app

import android.app.Application

/**
 * 应用入口：持有全局单例（网络客户端 / 仓库 / 设置）。
 * 具体装配在 [AppContainer] 中完成，避免引入额外的 DI 框架。
 */
class SafeBankApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        lateinit var container: AppContainer
            private set
    }
}
