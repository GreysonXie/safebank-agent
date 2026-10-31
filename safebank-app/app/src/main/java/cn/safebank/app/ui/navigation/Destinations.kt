package cn.safebank.app.ui.navigation

/**
 * App 内的全部目的地。底部导航 5 个 tab（对话/账单/代扣/储蓄/更多），
 * 其余为二级页面（push 到返回栈，显示自带标题栏与返回按钮）。
 */
sealed interface AppDestination {
    val title: String
    val isTab: Boolean

    data object Chat : AppDestination {
        override val title = "对话助手"
        override val isTab = true
    }

    data object Statement : AppDestination {
        override val title = "账单分析"
        override val isTab = true
    }

    data object Mandates : AppDestination {
        override val title = "代扣管理"
        override val isTab = true
    }

    data object Savings : AppDestination {
        override val title = "储蓄任务"
        override val isTab = true
    }

    data object More : AppDestination {
        override val title = "更多"
        override val isTab = true
    }

    // ---------------- 二级页面 ----------------
    data object Transfer : AppDestination {
        override val title = "准备一笔转账"
        override val isTab = false
    }

    data object Actions : AppDestination {
        override val title = "待确认操作与回执"
        override val isTab = false
    }

    data object Audit : AppDestination {
        override val title = "实验与审计"
        override val isTab = false
    }

    data object Settings : AppDestination {
        override val title = "设置"
        override val isTab = false
    }

    data class WorkflowDetail(val workflowId: String) : AppDestination {
        override val title = "联合任务"
        override val isTab = false
    }

    data object Finance : AppDestination {
        override val title = "金融推荐"
        override val isTab = false
    }

    data object Cards : AppDestination {
        override val title = "卡片管理"
        override val isTab = false
    }

    data object StatementExtras : AppDestination {
        override val title = "账单扩展"
        override val isTab = false
    }

    data object Users : AppDestination {
        override val title = "用户与联系人"
        override val isTab = false
    }

    companion object {
        val tabs: List<AppDestination>
            get() = listOf(Chat, Statement, Mandates, Savings, More)
    }
}
