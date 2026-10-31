package cn.safebank.app.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 极简返回栈导航（不引入 navigation-compose）。
 *
 * - 底部 tab 之间切换会清空返回栈（只保留当前 tab）；
 * - 二级页面 push 进栈，返回键 pop；
 * - 路由状态是 Compose 可观察的，因此可直接驱动重组。
 */
class AppNavigator(start: AppDestination = AppDestination.Statement) {

    private val stack: SnapshotStateList<AppDestination> = mutableStateListOf(start)

    val current: AppDestination get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    val depth: Int get() = stack.size

    fun push(destination: AppDestination) {
        if (stack.last() == destination) return
        stack.add(destination)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun selectTab(tab: AppDestination) {
        require(tab.isTab) { "selectTab 只接受底部 tab" }
        stack.clear()
        stack.add(tab)
    }

    /** 用于同一目的地的内容刷新（例如 workflowId 变化）。 */
    fun replaceTop(destination: AppDestination) {
        stack[stack.lastIndex] = destination
    }
}
